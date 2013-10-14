/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.converter.crypto;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.Security;
import java.security.SignatureException;
import java.util.Date;

import org.apache.camel.Exchange;
import org.apache.camel.spi.DataFormat;
import org.apache.camel.support.ServiceSupport;
import org.apache.camel.util.ExchangeHelper;
import org.apache.camel.util.IOHelper;
import org.apache.camel.util.ObjectHelper;
import org.apache.commons.io.IOUtils;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.CompressionAlgorithmTags;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPOnePassSignature;
import org.bouncycastle.openpgp.PGPOnePassSignatureList;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator;
import org.bouncycastle.util.io.Streams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <code>PGPDataFormat</code> uses the <a
 * href="http://www.bouncycastle.org/java.htm">bouncy castle</a> libraries to
 * enable encryption and decryption in the PGP format.
 */
public class PGPDataFormat extends ServiceSupport implements DataFormat {

    public static final String KEY_FILE_NAME = "CamelPGPDataFormatKeyFileName";
    public static final String ENCRYPTION_KEY_RING = "CamelPGPDataFormatEncryptionKeyRing";
    public static final String KEY_USERID = "CamelPGPDataFormatKeyUserid";
    public static final String KEY_PASSWORD = "CamelPGPDataFormatKeyPassword";
    public static final String SIGNATURE_KEY_FILE_NAME = "CamelPGPDataFormatSignatureKeyFileName";
    public static final String SIGNATURE_KEY_RING = "CamelPGPDataFormatSignatureKeyRing";
    public static final String SIGNATURE_KEY_USERID = "CamelPGPDataFormatSignatureKeyUserid";
    public static final String SIGNATURE_KEY_PASSWORD = "CamelPGPDataFormatSignatureKeyPassword";
    public static final String ENCRYPTION_ALGORITHM = "CamelPGPDataFormatEncryptionAlgorithm";
    public static final String SIGNATURE_HASH_ALGORITHM = "CamelPGPDataFormatSignatureHashAlgorithm";

    private static final Logger LOG = LoggerFactory.getLogger(PGPDataFormat.class);

    private static final String BC = "BC";
    private static final int BUFFER_SIZE = 16 * 1024;

    // Java Cryptography Extension provider, default is Bouncy Castle
    private String provider = BC;

    // encryption / decryption key info (required)
    private String keyUserid;
    private String password;
    private String keyFileName;
    // alternatively to the file name you can specify the key ring as byte array
    private byte[] encryptionKeyRing;

    // signature / verification key info (optional)
    private String signatureKeyUserid;
    private String signaturePassword;
    private String signatureKeyFileName;
    // alternatively to the signature key file name you can specify the signature key ring as byte array
    private byte[] signatureKeyRing;

    private boolean armored;
    private boolean integrity = true;
    
    /** Digest algorithm for signing (marshal).
     * Possible values are defined in {@link HashAlgorithmTags}.
     * Default value is SHA1.
     */
    private int hashAlgorithm = HashAlgorithmTags.SHA1;
    
    /**
     * Symmetric key algorithm for encryption (marschal).
     * Possible values are defined in {@link SymmetricKeyAlgorithmTags}.
     * Default value is CAST5.
     */
    private int algorithm = SymmetricKeyAlgorithmTags.CAST5;

    public PGPDataFormat() {
    }

    protected String findKeyFileName(Exchange exchange) {
        return exchange.getIn().getHeader(KEY_FILE_NAME, getKeyFileName(), String.class);
    }

    protected byte[] findEncryptionKeyRing(Exchange exchange) {
        return exchange.getIn().getHeader(ENCRYPTION_KEY_RING, getEncryptionKeyRing(), byte[].class);
    }

    protected String findKeyUserid(Exchange exchange) {
        return exchange.getIn().getHeader(KEY_USERID, getKeyUserid(), String.class);
    }

    protected String findKeyPassword(Exchange exchange) {
        return exchange.getIn().getHeader(KEY_PASSWORD, getPassword(), String.class);
    }

    protected String findSignatureKeyFileName(Exchange exchange) {
        return exchange.getIn().getHeader(SIGNATURE_KEY_FILE_NAME, getSignatureKeyFileName(), String.class);
    }

    protected byte[] findSignatureKeyRing(Exchange exchange) {
        return exchange.getIn().getHeader(SIGNATURE_KEY_RING, getSignatureKeyRing(), byte[].class);
    }

    protected String findSignatureKeyUserid(Exchange exchange) {
        return exchange.getIn().getHeader(SIGNATURE_KEY_USERID, getSignatureKeyUserid(), String.class);
    }

    protected String findSignatureKeyPassword(Exchange exchange) {
        return exchange.getIn().getHeader(SIGNATURE_KEY_PASSWORD, getSignaturePassword(), String.class);
    }
    
    protected int findAlgorithm(Exchange exchange) {
        return exchange.getIn().getHeader(ENCRYPTION_ALGORITHM, getAlgorithm(), Integer.class);
    }

    protected int findHashAlgorithm(Exchange exchange) {
        return exchange.getIn().getHeader(SIGNATURE_HASH_ALGORITHM, getHashAlgorithm(), Integer.class);
    }

    public void marshal(Exchange exchange, Object graph, OutputStream outputStream) throws Exception {
        PGPPublicKey key = PGPDataFormatUtil.findPublicKey(exchange.getContext(), findKeyFileName(exchange),
                findEncryptionKeyRing(exchange), findKeyUserid(exchange), true);
        if (key == null) {
            throw new IllegalArgumentException("Public key is null, cannot proceed");
        }

        InputStream input = ExchangeHelper.convertToMandatoryType(exchange, InputStream.class, graph);

        if (armored) {
            outputStream = new ArmoredOutputStream(outputStream);
        }

        PGPEncryptedDataGenerator encGen = new PGPEncryptedDataGenerator(new JcePGPDataEncryptorBuilder(findAlgorithm(exchange))
                .setWithIntegrityPacket(integrity).setSecureRandom(new SecureRandom()).setProvider(getProvider()));
        encGen.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(key));
        OutputStream encOut = encGen.open(outputStream, new byte[BUFFER_SIZE]);

        PGPCompressedDataGenerator comData = new PGPCompressedDataGenerator(CompressionAlgorithmTags.ZIP);
        OutputStream comOut = new BufferedOutputStream(comData.open(encOut));

        PGPSignatureGenerator sigGen = createSignatureGenerator(exchange, comOut);

        PGPLiteralDataGenerator litData = new PGPLiteralDataGenerator();
        String fileName = exchange.getIn().getHeader(Exchange.FILE_NAME, String.class);
        if (ObjectHelper.isEmpty(fileName)) {
            // This marks the file as For Your Eyes Only... may cause problems for the receiver if they use
            // an automated process to decrypt as the filename is appended with _CONSOLE
            fileName = PGPLiteralData.CONSOLE;
        }
        OutputStream litOut = litData.open(comOut, PGPLiteralData.BINARY, fileName, new Date(), new byte[BUFFER_SIZE]);

        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                litOut.write(buffer, 0, bytesRead);
                if (sigGen != null) {
                    sigGen.update(buffer, 0, bytesRead);
                }
                litOut.flush();
            }
        } finally {
            IOHelper.close(litOut);
            if (sigGen != null) {
                sigGen.generate().encode(comOut);
            }
            IOHelper.close(comOut, encOut, outputStream, input);
        }
    }

    protected PGPSignatureGenerator createSignatureGenerator(Exchange exchange, OutputStream out) throws IOException, PGPException,
            NoSuchProviderException, NoSuchAlgorithmException {

        String sigKeyFileName = findSignatureKeyFileName(exchange);
        String sigKeyUserid = findSignatureKeyUserid(exchange);
        String sigKeyPassword = findSignatureKeyPassword(exchange);
        byte[] sigKeyRing = findSignatureKeyRing(exchange);

        if ((sigKeyFileName == null && sigKeyRing == null) || sigKeyUserid == null || sigKeyPassword == null) {
            return null;
        }

        PGPSecretKey sigSecretKey = PGPDataFormatUtil.findSecretKey(exchange.getContext(), sigKeyFileName, sigKeyRing, sigKeyPassword, getProvider());
        if (sigSecretKey == null) {
            throw new IllegalArgumentException("Signature secret key is null, cannot proceed");
        }

        PGPPrivateKey sigPrivateKey = sigSecretKey.extractPrivateKey(new JcePBESecretKeyDecryptorBuilder().setProvider(getProvider()).build(
                sigKeyPassword.toCharArray()));
        if (sigPrivateKey == null) {
            throw new IllegalArgumentException("Signature private key is null, cannot proceed");
        }

        PGPSignatureSubpacketGenerator spGen = new PGPSignatureSubpacketGenerator();
        spGen.setSignerUserID(false, sigKeyUserid);

        int algorithm = sigSecretKey.getPublicKey().getAlgorithm();
        PGPSignatureGenerator sigGen = new PGPSignatureGenerator(
                new JcaPGPContentSignerBuilder(algorithm, findHashAlgorithm(exchange)).setProvider(getProvider()));
        sigGen.init(PGPSignature.BINARY_DOCUMENT, sigPrivateKey);
        sigGen.setHashedSubpackets(spGen.generate());
        sigGen.generateOnePassVersion(false).encode(out);
        return sigGen;
    }

    public Object unmarshal(Exchange exchange, InputStream encryptedStream) throws Exception {
        if (encryptedStream == null) {
            return null;
        }

        PGPPrivateKey key = PGPDataFormatUtil.findPrivateKey(exchange.getContext(), findKeyFileName(exchange),
                findEncryptionKeyRing(exchange), encryptedStream, findKeyPassword(exchange), getProvider());
        if (key == null) {
            throw new IllegalArgumentException("Private key is null, cannot proceed");
        }

        InputStream in;
        try {
            byte[] encryptedData = IOUtils.toByteArray(encryptedStream);
            InputStream byteStream = new ByteArrayInputStream(encryptedData);
            in = PGPUtil.getDecoderStream(byteStream);
        } finally {
            IOUtils.closeQuietly(encryptedStream);
        }

        PGPObjectFactory pgpFactory = new PGPObjectFactory(in);
        Object o = pgpFactory.nextObject();

        // the first object might be a PGP marker packet
        PGPEncryptedDataList enc;
        if (o instanceof PGPEncryptedDataList) {
            enc = (PGPEncryptedDataList) o;
        } else {
            enc = (PGPEncryptedDataList) pgpFactory.nextObject();
        }
        IOHelper.close(in);

        PGPPublicKeyEncryptedData pbe = (PGPPublicKeyEncryptedData) enc.get(0);
        InputStream encData = pbe.getDataStream(new JcePublicKeyDataDecryptorFactoryBuilder().setProvider(getProvider()).build(key));
        pgpFactory = new PGPObjectFactory(encData);
        PGPCompressedData comData = (PGPCompressedData) pgpFactory.nextObject();

        pgpFactory = new PGPObjectFactory(comData.getDataStream());
        Object object = pgpFactory.nextObject();

        PGPOnePassSignature signature;
        if (object instanceof PGPOnePassSignatureList) {
            signature = getSignature(exchange, (PGPOnePassSignatureList) object);
            object = pgpFactory.nextObject();
        } else {
            signature = null;
        }

        PGPLiteralData ld = (PGPLiteralData) object;
        InputStream litData = ld.getInputStream();

        byte[] answer;
        try {
            answer = Streams.readAll(litData);
        } finally {
            IOHelper.close(litData, encData, in);
        }

        if (signature != null) {
            signature.update(answer);
            PGPSignatureList sigList = (PGPSignatureList) pgpFactory.nextObject();
            if (!signature.verify(sigList.get(0))) {
                throw new SignatureException("Cannot verify PGP signature");
            }
        }

        return answer;
    }

    protected PGPOnePassSignature getSignature(Exchange exchange, PGPOnePassSignatureList signatureList) throws IOException, PGPException,
            NoSuchProviderException {

        PGPPublicKey sigPublicKey = PGPDataFormatUtil.findPublicKey(exchange.getContext(), findSignatureKeyFileName(exchange),
                findSignatureKeyRing(exchange), findSignatureKeyUserid(exchange), false);
        if (sigPublicKey == null) {
            throw new IllegalArgumentException("Signature public key is null, cannot proceed");
        }

        PGPOnePassSignature signature = signatureList.get(0);
        signature.init(new JcaPGPContentVerifierBuilderProvider().setProvider(getProvider()), sigPublicKey);
        return signature;
    }

    /**
     * Sets if the encrypted file should be written in ascii visible text
     */
    public void setArmored(boolean armored) {
        this.armored = armored;
    }

    public boolean getArmored() {
        return this.armored;
    }

    /**
     * Whether or not to add a integrity check/sign to the encrypted file
     */
    public void setIntegrity(boolean integrity) {
        this.integrity = integrity;
    }

    public boolean getIntegrity() {
        return this.integrity;
    }

    /**
     * Userid of the key used to encrypt/decrypt
     */
    public void setKeyUserid(String keyUserid) {
        this.keyUserid = keyUserid;
    }

    public String getKeyUserid() {
        return keyUserid;
    }

    /**
     * filename of the keyring that will be used, classpathResource
     */
    public void setKeyFileName(String keyFileName) {
        this.keyFileName = keyFileName;
    }

    public String getKeyFileName() {
        return keyFileName;
    }

    /**
     * Password used to open the private keyring
     */
    public void setPassword(String password) {
        this.password = password;
    }

    public String getPassword() {
        return password;
    }

    /**
     * Userid of the signature key used to sign/verify
     */
    public void setSignatureKeyUserid(String signatureKeyUserid) {
        this.signatureKeyUserid = signatureKeyUserid;
    }

    public String getSignatureKeyUserid() {
        return signatureKeyUserid;
    }

    /**
     * filename of the signature keyring that will be used, classpathResource
     */
    public void setSignatureKeyFileName(String signatureKeyFileName) {
        this.signatureKeyFileName = signatureKeyFileName;
    }

    public String getSignatureKeyFileName() {
        return signatureKeyFileName;
    }

    /**
     * Password used to open the signature private keyring
     */
    public void setSignaturePassword(String signaturePassword) {
        this.signaturePassword = signaturePassword;
    }

    public String getSignaturePassword() {
        return signaturePassword;
    }

    public byte[] getEncryptionKeyRing() {
        return encryptionKeyRing;
    }

    public void setEncryptionKeyRing(byte[] encryptionKeyRing) {
        this.encryptionKeyRing = encryptionKeyRing;
    }

    public byte[] getSignatureKeyRing() {
        return signatureKeyRing;
    }

    public void setSignatureKeyRing(byte[] signatureKeyRing) {
        this.signatureKeyRing = signatureKeyRing;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }
    
    

    public int getHashAlgorithm() {
        return hashAlgorithm;
    }

    public void setHashAlgorithm(int hashAlgorithm) {
        this.hashAlgorithm = hashAlgorithm;
    }

    public int getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(int algorithm) {
        this.algorithm = algorithm;
    }

    @Override
    protected void doStart() throws Exception {
        if (Security.getProvider(BC) == null && BC.equals(getProvider())) {
            LOG.debug("Adding BouncyCastleProvider as security provider");
            Security.addProvider(new BouncyCastleProvider());
        } else {
            LOG.debug("Using custom provider {} which is expected to be enlisted manually.", getProvider());
        }
    }

    @Override
    protected void doStop() throws Exception {
        // noop
    }
}
