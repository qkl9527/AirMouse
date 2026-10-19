package dev.airmouse.tv

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.KeyManager

/**
 * 为电视本地 HTTPS 页面准备短期服务端证书和长期信任根。
 *
 * 私钥仅保存在应用私有目录；首次启动时生成本地 CA，后续按电视当前局域网 IP
 * 签发带 IP SAN 的服务证书。手机只需信任该本地 CA，普通 HTTP 遥控不加载此身份。
 */
internal object LocalTlsIdentity {
    private const val CA_ALIAS = "airmouse-local-ca"
    private const val STORE_FILE = "airmouse-local-ca.p12"
    private const val STORE_PASSWORD = "airmouse-local-ca-store"

    data class ServerIdentity(
        val keyManagers: Array<KeyManager>,
        val caCertificatePem: String,
        val fingerprint: String,
    )

    fun create(context: Context, hostAddress: String): ServerIdentity {
        if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
        val root = loadOrCreateRoot(context.applicationContext)
        val leafPair = generateRsaKeyPair()
        val leafCertificate = issueServerCertificate(root, leafPair, hostAddress)

        // JSSE 需要服务端私钥和完整证书链；该临时 KeyStore 只保留在内存中。
        val password = STORE_PASSWORD.toCharArray()
        val serverStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, password)
            setKeyEntry("airmouse-server", leafPair.private, password, arrayOf(leafCertificate, root.certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).run {
            init(serverStore, password)
            keyManagers
        }
        return ServerIdentity(
            keyManagers = keyManagers,
            caCertificatePem = toPem(root.certificate),
            fingerprint = sha256Fingerprint(root.certificate),
        )
    }

    private data class RootIdentity(val keyPair: KeyPair, val certificate: X509Certificate)

    private fun loadOrCreateRoot(context: Context): RootIdentity {
        val file = File(context.filesDir, STORE_FILE)
        if (file.exists()) {
            runCatching {
                val store = KeyStore.getInstance("PKCS12")
                FileInputStream(file).use { store.load(it, STORE_PASSWORD.toCharArray()) }
                val key = store.getKey(CA_ALIAS, STORE_PASSWORD.toCharArray()) as java.security.PrivateKey
                val cert = store.getCertificate(CA_ALIAS) as X509Certificate
                cert.checkValidity()
                return RootIdentity(KeyPair(cert.publicKey, key), cert)
            }
        }

        val pair = generateRsaKeyPair()
        val certificate = issueRootCertificate(pair)
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, STORE_PASSWORD.toCharArray())
            setKeyEntry(CA_ALIAS, pair.private, STORE_PASSWORD.toCharArray(), arrayOf(certificate))
        }
        FileOutputStream(file).use { store.store(it, STORE_PASSWORD.toCharArray()) }
        return RootIdentity(pair, certificate)
    }

    private fun generateRsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply {
        initialize(2048, SecureRandom())
    }.generateKeyPair()

    private fun issueRootCertificate(pair: KeyPair): X509Certificate {
        val name = X500Name("CN=AirMouse TV Local Certificate Authority")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger(160, SecureRandom()).abs(),
            Date(now - 60_000),
            Date(now + 10L * 365 * 24 * 60 * 60 * 1000),
            name,
            pair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature),
        )
        return signCertificate(builder, pair.private, pair.public)
    }

    private fun issueServerCertificate(root: RootIdentity, pair: KeyPair, hostAddress: String): X509Certificate {
        val issuer = X500Name(root.certificate.subjectX500Principal.name)
        val subject = X500Name("CN=AirMouse TV ($hostAddress)")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger(160, SecureRandom()).abs(),
            Date(now - 60_000),
            Date(now + 90L * 24 * 60 * 60 * 1000),
            subject,
            pair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
        )
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        // 局域网页面用 IP 地址访问，DNS 名称无法匹配；将当前地址写进标准 IP SAN。
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(GeneralName(GeneralName.iPAddress, hostAddress)),
        )
        return signCertificate(builder, root.keyPair.private, root.keyPair.public)
    }

    private fun signCertificate(
        builder: JcaX509v3CertificateBuilder,
        signingKey: java.security.PrivateKey,
        issuerPublicKey: java.security.PublicKey,
    ): X509Certificate {
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(signingKey)
        return JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer)).apply {
            verify(issuerPublicKey)
        }
    }

    private fun toPem(certificate: X509Certificate): String = StringWriter().use { writer ->
        JcaPEMWriter(writer).use { it.writeObject(certificate) }
        writer.toString()
    }

    private fun sha256Fingerprint(certificate: X509Certificate): String = certificate.encoded
        .let { java.security.MessageDigest.getInstance("SHA-256").digest(it) }
        .joinToString(":") { byte -> "%02X".format(byte) }
}
