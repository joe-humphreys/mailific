/*-
 * Mailific SMTP Server Library
 *
 * Copyright (C) 2023 Joe Humphreys
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.mailific.spf;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.mailific.spf.dns.DnsFail;
import net.mailific.spf.dns.InvalidName;
import net.mailific.spf.dns.NameResolver;
import net.mailific.spf.dns.RuntimeDnsFail;
import net.mailific.spf.parser.ParseException;
import net.mailific.spf.policy.Directive;
import net.mailific.spf.policy.Policy;
import net.mailific.spf.policy.PolicySyntaxException;

/** Stateful class that tracks the current SPF check and provides needed utilities. */
public class SpfUtilImp implements SpfUtil {

  private int lookupsUsed = 0;
  private int voidLookupsUsed = 0;
  private NameResolver resolver;
  private Settings settings;

  public SpfUtilImp(NameResolver resolver, Settings settings) {
    this.resolver = resolver;
    this.settings = settings;
  }

  public Result checkHost(InetAddress ip, String domain, String sender, String ehloParam) {
    try {
      verifyDomain(domain);
      String spfRecord = lookupSpfRecord(domain);
      Policy policy = Policy.parse(spfRecord, settings.getExplainPrefix());
      Result result = null;
      for (Directive directive : policy.getDirectives()) {
        result = directive.evaluate(this, ip, domain, sender, ehloParam);
        if (result != null) {
          break;
        }
      }
      if (result == null && policy.getRedirect() != null) {
        incLookupCounter();
        String redirectDomain =
            policy.getRedirect().getDomainSpec().expand(this, ip, domain, sender, ehloParam);
        result = checkHost(ip, redirectDomain, sender, ehloParam);
        if (result.getCode() == ResultCode.None) {
          result = new Result(ResultCode.Permerror, result.getExplanation());
        }
      } else if (result != null
          && result.getCode() == ResultCode.Fail
          && policy.getExplanation() != null) {
        String explanation = policy.getExplanation().expand(this, ip, domain, sender, ehloParam);
        if (explanation != null) {
          result = new Result(result.getCode(), explanation);
        }
      }
      if (result == null) {
        result = new Result(ResultCode.Neutral, "No directives matched.");
      }
      return result;
    } catch (ParseException | PolicySyntaxException e) {
      return new Result(ResultCode.Permerror, "Invalid spf record syntax.");
    } catch (Abort e) {
      return e.result;
    }
  }

  private static boolean hasSpfVersion(String s) {
    // The ABNF says it's case insensitive. The spec says '...exactly
    // "v=spf1"'. But there's no formal definition of exactly, and
    // nothing explicitly calls out an exception to the ABNF. So I'm
    // going with case-insensitive.
    return s != null && s.length() >= 6 && s.substring(0, 6).equalsIgnoreCase("v=spf1");
  }

  public String lookupSpfRecord(String domain) throws Abort {
    try {
      List<String> txtRecords = resolveTxtRecords(domain);
      txtRecords =
          txtRecords.stream().filter(SpfUtilImp::hasSpfVersion).collect(Collectors.toList());
      if (txtRecords.size() < 1) {
        throw new Abort(ResultCode.None, "No SPF record found for: " + domain);
      }
      if (txtRecords.size() > 1) {
        throw new Abort(ResultCode.Permerror, "Multiple SPF records found for: " + domain);
      }
      return txtRecords.get(0);
    } catch (InvalidName e) {
      throw new Abort(ResultCode.None, e.getMessage());
    } catch (DnsFail e) {
      throw new Abort(ResultCode.Temperror, e.getMessage());
    }
  }

  /* Probably we could just skip this, or limit it to the small number of checks that
   * would not always result in a lookup failure.
   */
  private void verifyDomain(String domain) throws Abort {
    if (domain == null) {
      throw new Abort(ResultCode.None, "Null domain");
    }
    if (domain.length() > 255) {
      throw new Abort(ResultCode.None, "Domain too long: " + domain);
    }
    String[] labels = domain.split("[.]");
    if (labels.length < 2) {
      throw new Abort(ResultCode.None, "Domain not FQDN: " + domain);
    }
    for (int i = 0; i < labels.length; i++) {
      if (labels[i].isEmpty()) {
        throw new Abort(ResultCode.None, "Domain contains 0-length label: " + domain);
      }
      if (labels[i].length() > 63) {
        throw new Abort(ResultCode.None, "Domain label > 63 chars: " + domain);
      }
    }
  }

  @Override
  public NameResolver getNameResolver() {
    return resolver;
  }

  /**
   * @param ip
   * @param domain
   * @return null if not validated
   * @throws NameNotFound
   * @throws DnsFail
   */
  @Override
  public String validatedHostForIp(InetAddress ip, String domain, boolean requireMatch)
      throws DnsFail, Abort {
    String ptrName = SpfUtil.ptrName(ip);
    List<String> results = getNameResolver().resolvePtrRecords(ptrName);
    if (results.isEmpty()) {
      incVoidLookupCounter();
      return null;
    }
    String match = null;
    Set<String> subdomains = new HashSet<>();
    Set<String> fallbacks = new HashSet<>();
    int i = 0;
    for (String result : results) {
      if (++i > 10) {
        break;
      }
      if (result.equalsIgnoreCase(domain)) {
        match = result;
      } else if (result.endsWith(domain)) {
        subdomains.add(result);
      } else {
        fallbacks.add(result);
      }
    }
    try {
      if (match != null && nameHasIp(match, ip)) {
        return match;
      }
      match = subdomains.stream().filter(s -> nameHasIp(s, ip)).findAny().orElse(null);
      if (match != null) {
        return match;
      }
      if (requireMatch) {
        return null;
      }
      return fallbacks.stream().filter(s -> nameHasIp(s, ip)).findAny().orElse(null);
    } catch (RuntimeAbort e) {
      throw e.getAbort();
    }
  }

  private boolean nameHasIp(String name, InetAddress ip) throws RuntimeAbort {
    try {
      List<InetAddress> ips = getIpsByHostnameRte(name, ip instanceof Inet4Address);
      return ips.stream().anyMatch(i -> i.equals(ip));
    } catch (RuntimeDnsFail e) {
      // If a DNS error occurs while doing an A RR lookup,
      // then that domain name is skipped and the search continues.
      return false;
    }
  }

  public int incLookupCounter() throws Abort {
    if (++lookupsUsed > settings.getLookupLimit()) {
      throw new Abort(ResultCode.Permerror, "Maximum total DNS lookups exceeded.");
    }
    return settings.getLookupLimit() - lookupsUsed;
  }

  public int incVoidLookupCounter() throws Abort {
    if (++voidLookupsUsed > settings.getVoidLookupLimit()) {
      throw new Abort(ResultCode.Permerror, "Maximum DNS void lookups exceeded.");
    }
    return settings.getVoidLookupLimit() - voidLookupsUsed;
  }

  @Override
  public List<InetAddress> getIpsByMxName(String name, boolean ip4) throws DnsFail, Abort {
    List<String> names = getNameResolver().resolveMXRecords(name);
    if (names.isEmpty()) {
      incVoidLookupCounter();
      return Collections.emptyList();
    }

    Set<String> nameSet = new HashSet<>(names);
    if (nameSet.size() > settings.getLookupLimit()) {
      throw new Abort(ResultCode.Permerror, "More than 10 MX records for " + name);
    }
    try {
      return names.stream()
          .flatMap(n -> getIpsByHostnameRte(n, ip4).stream())
          .distinct()
          .collect(Collectors.toList());
    } catch (RuntimeDnsFail e) {
      throw e.getDnsFail();
    } catch (RuntimeAbort e) {
      throw e.getAbort();
    }
  }

  private List<InetAddress> getIpsByHostnameRte(String name, boolean ip4)
      throws RuntimeDnsFail, RuntimeAbort {
    try {
      return getIpsByHostname(name, ip4);
    } catch (DnsFail e) {
      throw new RuntimeDnsFail(e);
    } catch (Abort e) {
      throw new RuntimeAbort(e);
    }
  }

  public List<InetAddress> getIpsByHostname(String name, boolean ip4) throws DnsFail, Abort {
    List<InetAddress> rv =
        ip4 ? getNameResolver().resolveARecords(name) : getNameResolver().resolveAAAARecords(name);
    if (rv.isEmpty()) {
      incVoidLookupCounter();
      return Collections.emptyList();
    }
    return rv;
  }

  private static final int[] MASKS = {
    0, 0b10000000, 0b11000000, 0b11100000, 0b11110000, 0b11111000, 0b11111100, 0b11111110
  };

  @Override
  public boolean cidrMatch(InetAddress ip1, InetAddress ip2, int bits) {
    // TODO: null checks
    if (bits < 0) {
      return ip1.equals(ip2);
    }
    byte[] ip1Bytes = ip1.getAddress();
    byte[] ip2Bytes = ip2.getAddress();
    if (ip1Bytes.length != ip2Bytes.length) {
      return false;
    }
    if (bits == 0) {
      return true;
    }
    // If more bits are specified than there are in the address,
    // compare all bits
    if (bits > ip1Bytes.length * 8) {
      bits = ip1Bytes.length * 8;
    }
    int i = 0;
    while (bits >= 8) {
      if (ip1Bytes[i] != ip2Bytes[i]) {
        return false;
      }
      ++i;
      bits -= 8;
    }
    if (bits > 0) {
      int mask = MASKS[bits];
      if ((ip1Bytes[i] & mask) != (ip2Bytes[i] & mask)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Note that this method does not increment the void lookup counter. That's because it's used by
   * Explanation, where it would make little sense to switch to a Permerror after you've already
   * gotten a failure and are just trying to look up the explanation.
   */
  @Override
  public List<String> resolveTxtRecords(String name) throws DnsFail {
    return getNameResolver().resolveTxtRecords(name);
  }

  @Override
  public String getHostDomain() {
    return settings.getHostDomain();
  }
}
