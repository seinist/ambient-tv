package com.liskovsoft.sharedutils.okhttp;

import org.junit.Test;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;
import org.xbill.DNS.config.JndiContextResolverConfigProvider;
import org.xbill.DNS.config.WindowsResolverConfigProvider;

import static org.junit.Assert.*;

public class DnsjavaAndroidCompatibilityTest {
    @Test
    public void androidDoesNotInitializeDesktopProviders() throws Exception {
        String vendor = System.getProperty("java.vendor");
        String os = System.getProperty("os.name");
        try {
            System.setProperty("java.vendor", "The Android Project");
            System.setProperty("os.name", "Linux");
            assertFalse(new WindowsResolverConfigProvider().isEnabled());
            assertFalse(new JndiContextResolverConfigProvider().isEnabled());
        } finally {
            if (vendor == null) System.clearProperty("java.vendor");
            else System.setProperty("java.vendor", vendor);
            if (os == null) System.clearProperty("os.name");
            else System.setProperty("os.name", os);
        }
    }

    @Test
    public void explicitResolverAndLookupCanBeConstructedWithoutWindowsJna() throws Exception {
        SimpleResolver resolver = new SimpleResolver("127.0.0.1");
        resolver.setTimeout(1);
        for (int type : new int[] { Type.A, Type.AAAA }) {
            Lookup lookup = new Lookup("example.invalid.", type);
            lookup.setResolver(resolver);
            assertNotNull(lookup);
        }
        assertNotNull(PublicDnsResolver.google());
        assertNotNull(PublicDnsResolver.cloudflare());
    }
}
