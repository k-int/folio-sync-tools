
import groovy.grape.Grape

@GrabResolver(name='mvnRepository', root='http://central.maven.org/maven2/')
@GrabResolver(name='kint', root='http://nexus.k-int.com/content/repositories/releases')
@Grab('commons-codec:commons-codec:1.14')
@Grab('org.ini4j:ini4j:0.5.4')

import org.ini4j.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;


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


PrivateKey pk = getPrivateKey(private_key_file);

println("Yay: ${pk}");

public static PrivateKey getPrivateKey(String filename) throws Exception {
  println("Load ${filename}");
  byte[] keyBytes = Files.readAllBytes(Paths.get(filename));
  X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
  // PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
  KeyFactory kf = KeyFactory.getInstance("EC");  // Using EC over RSA now
  return kf.generatePrivate(spec);
}



public static PublicKey getPublicKey(String filename) throws Exception {
  byte[] keyBytes = Files.readAllBytes(Paths.get(filename));
  X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
  KeyFactory kf = KeyFactory.getInstance("EC");  // Using EC over RSA now
  return kf.generatePublic(spec);
}
