package org.bouncycastle.openpgp.test;

import org.bouncycastle.bcpg.ArmoredInputStream;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.BCPGOutputStream;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.util.encoders.Base64;
import org.bouncycastle.util.test.SimpleTest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SignatureException;
import java.util.Iterator;

public class PGPClearSignedSignatureTest 
    extends SimpleTest
{ 
    byte[] publicKey = Base64.decode(
       "mQELBEQh2+wBCAD26kte0hO6flr7Y2aetpPYutHY4qsmDPy+GwmmqVeCDkX+"
     + "r1g7DuFbMhVeu0NkKDnVl7GsJ9VarYsFYyqu0NzLa9XS2qlTIkmJV+2/xKa1"
     + "tzjn18fT/cnAWL88ZLCOWUr241aPVhLuIc6vpHnySpEMkCh4rvMaimnTrKwO"
     + "42kgeDGd5cXfs4J4ovRcTbc4hmU2BRVsRjiYMZWWx0kkyL2zDVyaJSs4yVX7"
     + "Jm4/LSR1uC/wDT0IJJuZT/gQPCMJNMEsVCziRgYkAxQK3OWojPSuv4rXpyd4"
     + "Gvo6IbvyTgIskfpSkCnQtORNLIudQSuK7pW+LkL62N+ohuKdMvdxauOnAAYp"
     + "tBNnZ2dnZ2dnZyA8Z2dnQGdnZ2c+iQE2BBMBAgAgBQJEIdvsAhsDBgsJCAcD"
     + "AgQVAggDBBYCAwECHgECF4AACgkQ4M/Ier3f9xagdAf/fbKWBjLQM8xR7JkR"
     + "P4ri8YKOQPhK+VrddGUD59/wzVnvaGyl9MZE7TXFUeniQq5iXKnm22EQbYch"
     + "v2Jcxyt2H9yptpzyh4tP6tEHl1C887p2J4qe7F2ATua9CzVGwXQSUbKtj2fg"
     + "UZP5SsNp25guhPiZdtkf2sHMeiotmykFErzqGMrvOAUThrO63GiYsRk4hF6r"
     + "cQ01d+EUVpY/sBcCxgNyOiB7a84sDtrxnX5BTEZDTEj8LvuEyEV3TMUuAjx1"
     + "7Eyd+9JtKzwV4v3hlTaWOvGro9nPS7YaPuG+RtufzXCUJPbPfTjTvtGOqvEz"
     + "oztls8tuWA0OGHba9XfX9rfgorACAAM=");

    byte[] secretKey = Base64.decode(
      "lQOWBEQh2+wBCAD26kte0hO6flr7Y2aetpPYutHY4qsmDPy+GwmmqVeCDkX+"
    + "r1g7DuFbMhVeu0NkKDnVl7GsJ9VarYsFYyqu0NzLa9XS2qlTIkmJV+2/xKa1"
    + "tzjn18fT/cnAWL88ZLCOWUr241aPVhLuIc6vpHnySpEMkCh4rvMaimnTrKwO"
    + "42kgeDGd5cXfs4J4ovRcTbc4hmU2BRVsRjiYMZWWx0kkyL2zDVyaJSs4yVX7"
    + "Jm4/LSR1uC/wDT0IJJuZT/gQPCMJNMEsVCziRgYkAxQK3OWojPSuv4rXpyd4"
    + "Gvo6IbvyTgIskfpSkCnQtORNLIudQSuK7pW+LkL62N+ohuKdMvdxauOnAAYp"
    + "AAf+JCJJeAXEcrTVHotsrRR5idzmg6RK/1MSQUijwPmP7ZGy1BmpAmYUfbxn"
    + "B56GvXyFV3Pbj9PgyJZGS7cY+l0BF4ZqN9USiQtC9OEpCVT5LVMCFXC/lahC"
    + "/O3EkjQy0CYK+GwyIXa+Flxcr460L/Hvw2ZEXJZ6/aPdiR+DU1l5h99Zw8V1"
    + "Y625MpfwN6ufJfqE0HLoqIjlqCfi1iwcKAK2oVx2SwnT1W0NwUUXjagGhD2s"
    + "VzJVpLqhlwmS0A+RE9Niqrf80/zwE7QNDF2DtHxmMHJ3RY/pfu5u1rrFg9YE"
    + "lmS60mzOe31CaD8Li0k5YCJBPnmvM9mN3/DWWprSZZKtmQQA96C2/VJF5EWm"
    + "+/Yxi5J06dG6Bkz311Ui4p2zHm9/4GvTPCIKNpGx9Zn47YFD3tIg3fIBVPOE"
    + "ktG38pEPx++dSSFF9Ep5UgmYFNOKNUVq3yGpatBtCQBXb1LQLAMBJCJ5TQmk"
    + "68hMOEaqjMHSOa18cS63INgA6okb/ueAKIHxYQcEAP9DaXu5n9dZQw7pshbN"
    + "Nu/T5IP0/D/wqM+W5r+j4P1N7PgiAnfKA4JjKrUgl8PGnI2qM/Qu+g3qK++c"
    + "F1ESHasnJPjvNvY+cfti06xnJVtCB/EBOA2UZkAr//Tqa76xEwYAWRBnO2Y+"
    + "KIVOT+nMiBFkjPTrNAD6fSr1O4aOueBhBAC6aA35IfjC2h5MYk8+Z+S4io2o"
    + "mRxUZ/dUuS+kITvWph2e4DT28Xpycpl2n1Pa5dCDO1lRqe/5JnaDYDKqxfmF"
    + "5tTG8GR4d4nVawwLlifXH5Ll7t5NcukGNMCsGuQAHMy0QHuAaOvMdLs5kGHn"
    + "8VxfKEVKhVrXsvJSwyXXSBtMtUcRtBNnZ2dnZ2dnZyA8Z2dnQGdnZ2c+iQE2"
    + "BBMBAgAgBQJEIdvsAhsDBgsJCAcDAgQVAggDBBYCAwECHgECF4AACgkQ4M/I"
    + "er3f9xagdAf/fbKWBjLQM8xR7JkRP4ri8YKOQPhK+VrddGUD59/wzVnvaGyl"
    + "9MZE7TXFUeniQq5iXKnm22EQbYchv2Jcxyt2H9yptpzyh4tP6tEHl1C887p2"
    + "J4qe7F2ATua9CzVGwXQSUbKtj2fgUZP5SsNp25guhPiZdtkf2sHMeiotmykF"
    + "ErzqGMrvOAUThrO63GiYsRk4hF6rcQ01d+EUVpY/sBcCxgNyOiB7a84sDtrx"
    + "nX5BTEZDTEj8LvuEyEV3TMUuAjx17Eyd+9JtKzwV4v3hlTaWOvGro9nPS7Ya"
    + "PuG+RtufzXCUJPbPfTjTvtGOqvEzoztls8tuWA0OGHba9XfX9rfgorACAAA=");

    String crOnlyMessage =
        "\r"
      + " hello world!\r"
      + "\r"
      + "- dash\r";
    
    String nlOnlyMessage =
          "\n"
        + " hello world!\n"
        + "\n"
        + "- dash\n";
    
    String crNlMessage =
          "\r\n"
        + " hello world!\r\n"
        + "\r\n"
        + "- dash\r\n";
    
    String crOnlySignedMessage =
        "-----BEGIN PGP SIGNED MESSAGE-----\r"
      + "Hash: SHA256\r"
      + "\r"
      + "\r"
      + " hello world!\r"
      + "\r"
      + "- - dash\r"
      + "-----BEGIN PGP SIGNATURE-----\r"
      + "Version: GnuPG v1.4.2.1 (GNU/Linux)\r"
      + "\r"
      + "iQEVAwUBRCNS8+DPyHq93/cWAQi6SwgAj3ItmSLr/sd/ixAQLW7/12jzEjfNmFDt\r"
      + "WOZpJFmXj0fnMzTrOILVnbxHv2Ru+U8Y1K6nhzFSR7d28n31/XGgFtdohDEaFJpx\r"
      + "Fl+KvASKIonnpEDjFJsPIvT1/G/eCPalwO9IuxaIthmKj0z44SO1VQtmNKxdLAfK\r"
      + "+xTnXGawXS1WUE4CQGPM45mIGSqXcYrLtJkAg3jtRa8YRUn2d7b2BtmWH+jVaVuC\r"
      + "hNrXYv7iHFOu25yRWhUQJisvdC13D/gKIPRvARXPgPhAC2kovIy6VS8tDoyG6Hm5\r"
      + "dMgLEGhmqsgaetVq1ZIuBZj5S4j2apBJCDpF6GBfpBOfwIZs0Tpmlw==\r"
      + "=84Nd\r"
      + "-----END PGP SIGNATURE-----\r";


    String nlOnlySignedMessage =
          "-----BEGIN PGP SIGNED MESSAGE-----\n"
        + "Hash: SHA256\n"
        + "\n"
        + "\n"
        + " hello world!\n"
        + "\n"
        + "- - dash\n"
        + "-----BEGIN PGP SIGNATURE-----\n"
        + "Version: GnuPG v1.4.2.1 (GNU/Linux)\n"
        + "\n"
        + "iQEVAwUBRCNS8+DPyHq93/cWAQi6SwgAj3ItmSLr/sd/ixAQLW7/12jzEjfNmFDt\n"
        + "WOZpJFmXj0fnMzTrOILVnbxHv2Ru+U8Y1K6nhzFSR7d28n31/XGgFtdohDEaFJpx\n"
        + "Fl+KvASKIonnpEDjFJsPIvT1/G/eCPalwO9IuxaIthmKj0z44SO1VQtmNKxdLAfK\n"
        + "+xTnXGawXS1WUE4CQGPM45mIGSqXcYrLtJkAg3jtRa8YRUn2d7b2BtmWH+jVaVuC\n"
        + "hNrXYv7iHFOu25yRWhUQJisvdC13D/gKIPRvARXPgPhAC2kovIy6VS8tDoyG6Hm5\n"
        + "dMgLEGhmqsgaetVq1ZIuBZj5S4j2apBJCDpF6GBfpBOfwIZs0Tpmlw==\n"
        + "=84Nd\n"
        + "-----END PGP SIGNATURE-----\n";
    
    String crNlSignedMessage =
        "-----BEGIN PGP SIGNED MESSAGE-----\r\n"
      + "Hash: SHA256\r\n"
      + "\r\n"
      + "\r\n"
      + " hello world!\r\n"
      + "\r\n"
      + "- - dash\r\n"
      + "-----BEGIN PGP SIGNATURE-----\r\n"
      + "Version: GnuPG v1.4.2.1 (GNU/Linux)\r\n"
      + "\r\n"
      + "iQEVAwUBRCNS8+DPyHq93/cWAQi6SwgAj3ItmSLr/sd/ixAQLW7/12jzEjfNmFDt\r\n"
      + "WOZpJFmXj0fnMzTrOILVnbxHv2Ru+U8Y1K6nhzFSR7d28n31/XGgFtdohDEaFJpx\r\n"
      + "Fl+KvASKIonnpEDjFJsPIvT1/G/eCPalwO9IuxaIthmKj0z44SO1VQtmNKxdLAfK\r\n"
      + "+xTnXGawXS1WUE4CQGPM45mIGSqXcYrLtJkAg3jtRa8YRUn2d7b2BtmWH+jVaVuC\r\n"
      + "hNrXYv7iHFOu25yRWhUQJisvdC13D/gKIPRvARXPgPhAC2kovIy6VS8tDoyG6Hm5\r\n"
      + "dMgLEGhmqsgaetVq1ZIuBZj5S4j2apBJCDpF6GBfpBOfwIZs0Tpmlw==\r\n"
      + "=84Nd\r"
      + "-----END PGP SIGNATURE-----\r\n";

    String crNlSignedMessageTrailingWhiteSpace =
        "-----BEGIN PGP SIGNED MESSAGE-----\r\n"
      + "Hash: SHA256\r\n"
      + "\r\n"
      + "\r\n"
      + " hello world! \t\r\n"
      + "\r\n"
      + "- - dash\r\n"
      + "-----BEGIN PGP SIGNATURE-----\r\n"
      + "Version: GnuPG v1.4.2.1 (GNU/Linux)\r\n"
      + "\r\n"
      + "iQEVAwUBRCNS8+DPyHq93/cWAQi6SwgAj3ItmSLr/sd/ixAQLW7/12jzEjfNmFDt\r\n"
      + "WOZpJFmXj0fnMzTrOILVnbxHv2Ru+U8Y1K6nhzFSR7d28n31/XGgFtdohDEaFJpx\r\n"
      + "Fl+KvASKIonnpEDjFJsPIvT1/G/eCPalwO9IuxaIthmKj0z44SO1VQtmNKxdLAfK\r\n"
      + "+xTnXGawXS1WUE4CQGPM45mIGSqXcYrLtJkAg3jtRa8YRUn2d7b2BtmWH+jVaVuC\r\n"
      + "hNrXYv7iHFOu25yRWhUQJisvdC13D/gKIPRvARXPgPhAC2kovIy6VS8tDoyG6Hm5\r\n"
      + "dMgLEGhmqsgaetVq1ZIuBZj5S4j2apBJCDpF6GBfpBOfwIZs0Tpmlw==\r\n"
      + "=84Nd\r"
      + "-----END PGP SIGNATURE-----\r\n";

    public String getName()
    {
        return "PGPClearSignedSignature";
    }

    private void messageTest(
        String message,
        String type)
        throws Exception
    {
        ArmoredInputStream aIn = new ArmoredInputStream(new ByteArrayInputStream(message.getBytes()));

        String[] headers = aIn.getArmorHeaders();
        
        if (headers == null || headers.length != 1)
        {
            fail("wrong number of headers found");
        }
        
        if (!"Hash: SHA256".equals(headers[0]))
        {
            fail("header value wrong: " + headers[0]);
        }
        
        //
        // read the input, making sure we ingore the last newline.
        //
        ByteArrayOutputStream bOut = new ByteArrayOutputStream();
        int                   ch;

        while ((ch = aIn.read()) >= 0 && aIn.isClearText())
        {
            bOut.write((byte)ch);
        }

        PGPPublicKeyRingCollection pgpRings = new PGPPublicKeyRingCollection(publicKey);

        PGPObjectFactory           pgpFact = new PGPObjectFactory(aIn);
        PGPSignatureList           p3 = (PGPSignatureList)pgpFact.nextObject();
        PGPSignature               sig = p3.get(0);
        
        sig.initVerify(pgpRings.getPublicKey(sig.getKeyID()), "BC");

        ByteArrayOutputStream lineOut = new ByteArrayOutputStream();
        InputStream           sigIn = new ByteArrayInputStream(bOut.toByteArray());
        int lookAhead = readInputLine(lineOut, sigIn);

        processLine(sig, lineOut.toByteArray());

        if (lookAhead != -1)
        {
            do
            {
                lookAhead = readInputLine(lineOut, lookAhead, sigIn);

                sig.update((byte)'\r');
                sig.update((byte)'\n');

                processLine(sig, lineOut.toByteArray());
            }
            while (lookAhead != -1);
        }

        if (!sig.verify())
        {
            fail("signature failed to verify in " + type);
        }
    }
    
    private PGPSecretKey readSecretKey(
        InputStream    in)
        throws IOException, PGPException
    {
        PGPSecretKeyRingCollection        pgpSec = new PGPSecretKeyRingCollection(in);

        PGPSecretKey    key = null;

        //
        // iterate through the key rings.
        //
        Iterator rIt = pgpSec.getKeyRings();

        while (key == null && rIt.hasNext())
        {
            PGPSecretKeyRing    kRing = (PGPSecretKeyRing)rIt.next();
            Iterator            kIt = kRing.getSecretKeys();
    
            while (key == null && kIt.hasNext())
            {
                PGPSecretKey    k = (PGPSecretKey)kIt.next();
    
                if (k.isSigningKey())
                {
                    key = k;
                }
            }
        }
    
        if (key == null)
        {
            throw new IllegalArgumentException("Can't find signing key in key ring.");
        }
    
        return key;
    }

    private void generateTest(
        String message,
        String type)
        throws Exception
    {
        PGPSecretKey                    pgpSecKey = readSecretKey(new ByteArrayInputStream(secretKey));
        PGPPrivateKey                   pgpPrivKey = pgpSecKey.extractPrivateKey("".toCharArray(), "BC");
        PGPSignatureGenerator           sGen = new PGPSignatureGenerator(pgpSecKey.getPublicKey().getAlgorithm(), PGPUtil.SHA256, "BC");
        PGPSignatureSubpacketGenerator  spGen = new PGPSignatureSubpacketGenerator();

        sGen.initSign(PGPSignature.CANONICAL_TEXT_DOCUMENT, pgpPrivKey);

        Iterator    it = pgpSecKey.getPublicKey().getUserIDs();
        if (it.hasNext())
        {
            spGen.setSignerUserID(false, (String)it.next());
            sGen.setHashedSubpackets(spGen.generate());
        }
        
        ByteArrayOutputStream  bOut = new ByteArrayOutputStream();
        ArmoredOutputStream    aOut = new ArmoredOutputStream(bOut);
        ByteArrayInputStream   bIn = new ByteArrayInputStream(message.getBytes());

        aOut.beginClearText(PGPUtil.SHA256);

        //
        // note the last \n in the file is ignored
        //
        ByteArrayOutputStream lineOut = new ByteArrayOutputStream();
        int lookAhead = readInputLine(lineOut, bIn);

        processLine(aOut, sGen, lineOut.toByteArray());

        if (lookAhead != -1)
        {
            do
            {
                lookAhead = readInputLine(lineOut, lookAhead, bIn);

                sGen.update((byte)'\r');
                sGen.update((byte)'\n');

                processLine(aOut, sGen, lineOut.toByteArray());
            }
            while (lookAhead != -1);
        }

        aOut.endClearText();

        BCPGOutputStream            bcpgOut = new BCPGOutputStream(aOut);

        sGen.generate().encode(bcpgOut);

        aOut.close();
        
        messageTest(new String(bOut.toByteArray()), type);
    }

    private static int readInputLine(ByteArrayOutputStream bOut, InputStream fIn)
        throws IOException
    {
        bOut.reset();

        int lookAhead = -1;
        int ch;

        while ((ch = fIn.read()) >= 0)
        {
            bOut.write(ch);
            if (ch == '\r' || ch == '\n')
            {
                lookAhead = readPassedEOL(bOut, ch, fIn);
                break;
            }
        }

        return lookAhead;
    }

    private static int readInputLine(ByteArrayOutputStream bOut, int lookAhead, InputStream fIn)
        throws IOException
    {
        bOut.reset();

        int ch = lookAhead;

        do
        {
            bOut.write(ch);
            if (ch == '\r' || ch == '\n')
            {
                lookAhead = readPassedEOL(bOut, ch, fIn);
                break;
            }
        }
        while ((ch = fIn.read()) >= 0);

        return lookAhead;
    }

    private static int readPassedEOL(ByteArrayOutputStream bOut, int lastCh, InputStream fIn)
        throws IOException
    {
        int lookAhead = fIn.read();

        if (lastCh == '\r' && lookAhead == '\n')
        {
            bOut.write(lookAhead);
            lookAhead = fIn.read();
        }

        return lookAhead;
    }

    private static void processLine(PGPSignature sig, byte[] line)
        throws SignatureException, IOException
    {
        int length = getLengthWithoutWhiteSpace(line);
        if (length > 0)
        {
            sig.update(line, 0, length);
        }
    }

    private static void processLine(OutputStream aOut, PGPSignatureGenerator sGen, byte[] line)
        throws SignatureException, IOException
    {
        int length = getLengthWithoutWhiteSpace(line);
        if (length > 0)
        {
            sGen.update(line, 0, length);
        }

        aOut.write(line, 0, line.length);
    }

    private static int getLengthWithoutWhiteSpace(byte[] line)
    {
        int    end = line.length - 1;

        while (end >= 0 && isWhiteSpace(line[end]))
        {
            end--;
        }

        return end + 1;
    }

    private static boolean isWhiteSpace(byte b)
    {
        return b == '\r' || b == '\n' || b == '\t' || b == ' ';
    }

    public void performTest()
        throws Exception
    {
        messageTest(crOnlySignedMessage, "\\r");
        messageTest(nlOnlySignedMessage, "\\n");
        messageTest(crNlSignedMessage, "\\r\\n");
        messageTest(crNlSignedMessageTrailingWhiteSpace, "\\r\\n");

        generateTest(nlOnlyMessage, "\\r");
        generateTest(crOnlyMessage, "\\n");
        generateTest(crNlMessage, "\\r\\n");
    }
    
    public static void main(
        String[]    args)
    {
        runTest(new PGPClearSignedSignatureTest());
    }
}
