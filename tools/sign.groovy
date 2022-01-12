
import groovy.grape.Grape

@GrabResolver(name='mvnRepository', root='http://central.maven.org/maven2/')
@GrabResolver(name='kint', root='http://nexus.k-int.com/content/repositories/releases')
@Grab('commons-codec:commons-codec:1.14')
@Grab('org.ini4j:ini4j:0.5.4')

import org.ini4j.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.security.interfaces.*;
import java.nio.charset.Charset;
import org.apache.commons.codec.binary.Base64;


println("mod-remote-sync sign Config");

def cli = new CliBuilder(usage: 'sign.groovy')

// Create the list of options.
cli.with {
  h longOpt: 'help', 'Show usage information'
  c longOpt: 'config', args: 1, argName: 'config', 'config name'
}

def options = cli.parse(args)
if (!options) {
  return
}

String cfgname=options.c ?: 'default'

Wini ini = new Wini(new File(System.getProperty("user.home")+'/.config/remote-sync-signing'));

String private_key_file = ini.get(cfgname, 'private_key', String.class);

println "sign.groovy"
if ( private_key_file == null ) {
  println("Invalid configuration - missing private key file");
  system.exit(1);
}


// PrivateKey pk = getPrivateKey(private_key_file);
println("start");
PublicKey pub_key = getPublicKey(new File('./mypublic.pem'));
println("got public key ${pub_key}");
PrivateKey priv_key = getPrivateKey(new File('./myprivate.pcks8'));
println("got private key ${priv_key}");

byte[] signature1 = getSignature('This is some text'.getBytes(), priv_key)
println("Signature#1: ${signature1}");

byte[] signature2 = getSignature('This is some different text'.getBytes(), priv_key)
println("Signature#1: ${signature2}");

public static RSAPublicKey getPublicKey(File file) throws Exception {
    String key = new String(Files.readAllBytes(file.toPath()), Charset.defaultCharset());

    String publicKeyPEM = key
      .replace("-----BEGIN PUBLIC KEY-----", "")
      .replaceAll(System.lineSeparator(), "")
      .replace("-----END PUBLIC KEY-----", "");

    byte[] encoded = Base64.decodeBase64(publicKeyPEM);

    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
    X509EncodedKeySpec keySpec = new X509EncodedKeySpec(encoded);
    return (RSAPublicKey) keyFactory.generatePublic(keySpec);
}

// N.B this only works to load a pcks8 encoded file and an openssl generated private key is not in that formay
// and must be converted with
//  openssl pkcs8 -topk8 -nocrypt -in myprivate.pem -out myprivate.pcks8
public RSAPrivateKey getPrivateKey(File file) throws Exception {
    String key = new String(Files.readAllBytes(file.toPath()), Charset.defaultCharset());

    String privateKeyPEM = key
      .replace("-----BEGIN PRIVATE KEY-----", "")
      .replaceAll(System.lineSeparator(), "")
      .replace("-----END PRIVATE KEY-----", "");

    byte[] encoded = Base64.decodeBase64(privateKeyPEM);

    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
    PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
    return (RSAPrivateKey) keyFactory.generatePrivate(keySpec);
}

public static byte[] getSignature(byte[] bytes, PrivateKey priv_key) {
  Signature sign = Signature.getInstance("SHA1withRSA");
  sign.initSign(priv_key);
  sign.update(bytes);
  return sign.sign();
}
