package org.bouncycastle.jce.provider.test;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.Date;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.Target;
import org.bouncycastle.asn1.x509.TargetInformation;
import org.bouncycastle.asn1.x509.X509Extensions;
import org.bouncycastle.jce.X509Principal;
import org.bouncycastle.jce.PrincipalUtil;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Base64;
import org.bouncycastle.util.test.SimpleTest;
import org.bouncycastle.util.test.Test;
import org.bouncycastle.util.test.TestResult;
import org.bouncycastle.x509.AttributeCertificateHolder;
import org.bouncycastle.x509.AttributeCertificateIssuer;
import org.bouncycastle.x509.X509Attribute;
import org.bouncycastle.x509.X509AttributeCertStoreSelector;
import org.bouncycastle.x509.X509AttributeCertificate;
import org.bouncycastle.x509.X509V2AttributeCertificateGenerator;

public class AttrCertSelectorTest
    extends SimpleTest
{

    static final RSAPrivateCrtKeySpec RSA_PRIVATE_KEY_SPEC = new RSAPrivateCrtKeySpec(
        new BigInteger(
            "b4a7e46170574f16a97082b22be58b6a2a629798419be12872a4bdba626cfae9900f76abfb12139dce5de56564fab2b6543165a040c606887420e33d91ed7ed7",
            16),
        new BigInteger("11", 16),
        new BigInteger(
            "9f66f6b05410cd503b2709e88115d55daced94d1a34d4e32bf824d0dde6028ae79c5f07b580f5dce240d7111f7ddb130a7945cd7d957d1920994da389f490c89",
            16), new BigInteger(
            "c0a0758cdf14256f78d4708c86becdead1b50ad4ad6c5c703e2168fbf37884cb",
            16), new BigInteger(
            "f01734d7960ea60070f1b06f2bb81bfac48ff192ae18451d5e56c734a5aab8a5",
            16), new BigInteger(
            "b54bb9edff22051d9ee60f9351a48591b6500a319429c069a3e335a1d6171391",
            16), new BigInteger(
            "d3d83daf2a0cecd3367ae6f8ae1aeb82e9ac2f816c6fc483533d8297dd7884cd",
            16), new BigInteger(
            "b8f52fc6f38593dabb661d3f50f8897f8106eee68b1bce78a95b132b4e5b5d19",
            16));

    static final byte[] holderCert = Base64
        .decode("MIIGjTCCBXWgAwIBAgICAPswDQYJKoZIhvcNAQEEBQAwaTEdMBsGCSqGSIb3DQEJ"
            + "ARYOaXJtaGVscEB2dC5lZHUxLjAsBgNVBAMTJVZpcmdpbmlhIFRlY2ggQ2VydGlm"
            + "aWNhdGlvbiBBdXRob3JpdHkxCzAJBgNVBAoTAnZ0MQswCQYDVQQGEwJVUzAeFw0w"
            + "MzAxMzExMzUyMTRaFw0wNDAxMzExMzUyMTRaMIGDMRswGQYJKoZIhvcNAQkBFgxz"
            + "c2hhaEB2dC5lZHUxGzAZBgNVBAMTElN1bWl0IFNoYWggKHNzaGFoKTEbMBkGA1UE"
            + "CxMSVmlyZ2luaWEgVGVjaCBVc2VyMRAwDgYDVQQLEwdDbGFzcyAxMQswCQYDVQQK"
            + "EwJ2dDELMAkGA1UEBhMCVVMwgZ8wDQYJKoZIhvcNAQEBBQADgY0AMIGJAoGBAPDc"
            + "scgSKmsEp0VegFkuitD5j5PUkDuzLjlfaYONt2SN8WeqU4j2qtlCnsipa128cyKS"
            + "JzYe9duUdNxquh5BPIkMkHBw4jHoQA33tk0J/sydWdN74/AHPpPieK5GHwhU7GTG"
            + "rCCS1PJRxjXqse79ExAlul+gjQwHeldAC+d4A6oZAgMBAAGjggOmMIIDojAMBgNV"
            + "HRMBAf8EAjAAMBEGCWCGSAGG+EIBAQQEAwIFoDAOBgNVHQ8BAf8EBAMCA/gwHQYD"
            + "VR0lBBYwFAYIKwYBBQUHAwIGCCsGAQUFBwMEMB0GA1UdDgQWBBRUIoWAzlXbzBYE"
            + "yVTjQFWyMMKo1jCBkwYDVR0jBIGLMIGIgBTgc3Fm+TGqKDhen+oKfbl+xVbj2KFt"
            + "pGswaTEdMBsGCSqGSIb3DQEJARYOaXJtaGVscEB2dC5lZHUxLjAsBgNVBAMTJVZp"
            + "cmdpbmlhIFRlY2ggQ2VydGlmaWNhdGlvbiBBdXRob3JpdHkxCzAJBgNVBAoTAnZ0"
            + "MQswCQYDVQQGEwJVU4IBADCBiwYJYIZIAYb4QgENBH4WfFZpcmdpbmlhIFRlY2gg"
            + "Q2VydGlmaWNhdGlvbiBBdXRob3JpdHkgZGlnaXRhbCBjZXJ0aWZpY2F0ZXMgYXJl"
            + "IHN1YmplY3QgdG8gcG9saWNpZXMgbG9jYXRlZCBhdCBodHRwOi8vd3d3LnBraS52"
            + "dC5lZHUvY2EvY3BzLy4wFwYDVR0RBBAwDoEMc3NoYWhAdnQuZWR1MBkGA1UdEgQS"
            + "MBCBDmlybWhlbHBAdnQuZWR1MEMGCCsGAQUFBwEBBDcwNTAzBggrBgEFBQcwAoYn"
            + "aHR0cDovL2JveDE3Ny5jYy52dC5lZHUvY2EvaXNzdWVycy5odG1sMEQGA1UdHwQ9"
            + "MDswOaA3oDWGM2h0dHA6Ly9ib3gxNzcuY2MudnQuZWR1L2h0ZG9jcy1wdWJsaWMv"
            + "Y3JsL2NhY3JsLmNybDBUBgNVHSAETTBLMA0GCysGAQQBtGgFAQEBMDoGCysGAQQB"
            + "tGgFAQEBMCswKQYIKwYBBQUHAgEWHWh0dHA6Ly93d3cucGtpLnZ0LmVkdS9jYS9j"
            + "cHMvMD8GCWCGSAGG+EIBBAQyFjBodHRwOi8vYm94MTc3LmNjLnZ0LmVkdS9jZ2kt"
            + "cHVibGljL2NoZWNrX3Jldl9jYT8wPAYJYIZIAYb4QgEDBC8WLWh0dHA6Ly9ib3gx"
            + "NzcuY2MudnQuZWR1L2NnaS1wdWJsaWMvY2hlY2tfcmV2PzBLBglghkgBhvhCAQcE"
            + "PhY8aHR0cHM6Ly9ib3gxNzcuY2MudnQuZWR1L35PcGVuQ0E4LjAxMDYzMC9jZ2kt"
            + "cHVibGljL3JlbmV3YWw/MCwGCWCGSAGG+EIBCAQfFh1odHRwOi8vd3d3LnBraS52"
            + "dC5lZHUvY2EvY3BzLzANBgkqhkiG9w0BAQQFAAOCAQEAHJ2ls9yjpZVcu5DqiE67"
            + "r7BfkdMnm7IOj2v8cd4EAlPp6OPBmjwDMwvKRBb/P733kLBqFNWXWKTpT008R0KB"
            + "8kehbx4h0UPz9vp31zhGv169+5iReQUUQSIwTGNWGLzrT8kPdvxiSAvdAJxcbRBm"
            + "KzDic5I8PoGe48kSCkPpT1oNmnivmcu5j1SMvlx0IS2BkFMksr0OHiAW1elSnE/N"
            + "RuX2k73b3FucwVxB3NRo3vgoHPCTnh9r4qItAHdxFlF+pPtbw2oHESKRfMRfOIHz"
            + "CLQWSIa6Tvg4NIV3RRJ0sbCObesyg08lymalQMdkXwtRn5eGE00SHWwEUjSXP2gR"
            + "3g==");

    public String getName()
    {
        return "AttrCertSelector";
    }

    private X509AttributeCertificate createAttrCert() throws Exception
    {
        CertificateFactory fact = CertificateFactory.getInstance("X.509", "BC");
        X509Certificate iCert = (X509Certificate) fact
            .generateCertificate(new ByteArrayInputStream(holderCert));

        //
        // a sample key pair.
        //
        // RSAPublicKeySpec pubKeySpec = new RSAPublicKeySpec(
        // new BigInteger(
        // "b4a7e46170574f16a97082b22be58b6a2a629798419be12872a4bdba626cfae9900f76abfb12139dce5de56564fab2b6543165a040c606887420e33d91ed7ed7",
        // 16), new BigInteger("11", 16));

        //
        // set up the keys
        //
        PrivateKey privKey;

        KeyFactory kFact = KeyFactory.getInstance("RSA", "BC");

        privKey = kFact.generatePrivate(RSA_PRIVATE_KEY_SPEC);

        X509V2AttributeCertificateGenerator gen = new X509V2AttributeCertificateGenerator();

        // the actual attributes
        GeneralName roleName = new GeneralName(GeneralName.rfc822Name,
            "DAU123456789@test.com");
        ASN1EncodableVector roleSyntax = new ASN1EncodableVector();
        roleSyntax.add(roleName);

        // roleSyntax OID: 2.5.24.72
        X509Attribute attributes = new X509Attribute("2.5.24.72",
            new DERSequence(roleSyntax));

        gen.addAttribute(attributes);
        gen.setHolder(new AttributeCertificateHolder(PrincipalUtil.getSubjectX509Principal(iCert)));
        gen.setIssuer(new AttributeCertificateIssuer(new X509Principal(
            "cn=test")));
        gen.setNotBefore(new Date(System.currentTimeMillis() - 50000));
        gen.setNotAfter(new Date(System.currentTimeMillis() + 50000));
        gen.setSerialNumber(BigInteger.valueOf(1));
        gen.setSignatureAlgorithm("SHA1WithRSAEncryption");

        Target targetName = new Target(Target.targetName, new GeneralName(GeneralName.dNSName,
            "www.test.com"));

        Target targetGroup = new Target(Target.targetGroup, new GeneralName(
            GeneralName.directoryName, "o=Test, ou=Test"));
        Target[] targets = new Target[2];
        targets[0] = targetName;
        targets[1] = targetGroup;
        TargetInformation targetInformation = new TargetInformation(targets);
        gen.addExtension(X509Extensions.TargetInformation.getId(), true,
            targetInformation);

        return gen.generate(privKey, "BC");
    }

    public void testSelector() throws Exception
    {
        X509AttributeCertificate aCert = createAttrCert();
        X509AttributeCertStoreSelector sel = new X509AttributeCertStoreSelector();
        sel.setAttributeCert(aCert);
        boolean match = sel.match(aCert);
        if (!match)
        {
            fail("Selector does not match attribute certificate.");
        }
        sel.setAttributeCert(null);
        match = sel.match(aCert);
        if (!match)
        {
            fail("Selector does not match attribute certificate.");
        }
        sel.setHolder(aCert.getHolder());
        match = sel.match(aCert);
        if (!match)
        {
            fail("Selector does not match attribute certificate holder.");
        }
        sel.setHolder(null);
        sel.setIssuer(aCert.getIssuer());
        match = sel.match(aCert);
        if (!match)
        {
            fail("Selector does not match attribute certificate issuer.");
        }
        sel.setIssuer(null);

        CertificateFactory fact = CertificateFactory.getInstance("X.509", "BC");
        X509Certificate iCert = (X509Certificate) fact
            .generateCertificate(new ByteArrayInputStream(holderCert));
        match = aCert.getHolder().match(iCert);
        if (!match)
        {
            fail("Issuer holder does not match signing certificate of attribute certificate.");
        }

        sel.setSerialNumber(aCert.getSerialNumber());
        match = sel.match(aCert);
        if (!match)
        {
            fail("Selector does not match attribute certificate serial number.");
        }

        sel.setAttributeCertificateValid(new Date());
        match = sel.match(aCert);
        if (!match)
        {
            fail("Selector does not match attribute certificate time.");
        }

        sel.addTargetName(new GeneralName(2, "www.test.com"));
        match = sel.match(aCert);
        if (!match)
        {
            fail("Selector does not match attribute certificate target name.");
        }
        sel.setTargetNames(null);
        sel.addTargetGroup(new GeneralName(4, "o=Test, ou=Test"));
        match = sel.match(aCert);
        if (!match)
        {
            fail("Selector does not match attribute certificate target group.");
        }
        sel.setTargetGroups(null);
    }

    public void performTest() throws Exception
    {
        Security.addProvider(new BouncyCastleProvider());
        testSelector();
    }

    public static void main(String[] args)
    {
        Test test = new AttrCertSelectorTest();
        TestResult result = test.perform();
        System.out.println(result);
    }
}

