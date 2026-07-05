const forge = require('node-forge');
const fs = require('fs');
const path = require('path');

// Generate RSA key pair
console.log('Generating RSA key pair...');
const keys = forge.pki.rsa.generateKeyPair(2048);

// Create a self-signed certificate
console.log('Creating self-signed certificate...');
const cert = forge.pki.createCertificate();
cert.publicKey = keys.publicKey;
cert.serialNumber = '01' + Date.now().toString(16);
cert.validity.notBefore = new Date();
cert.validity.notAfter = new Date();
cert.validity.notAfter.setFullYear(cert.validity.notBefore.getFullYear() + 27);

const attrs = [
  { name: 'commonName', value: 'Linxi Browser' },
  { name: 'organizationName', value: 'Linxi' },
  { name: 'organizationalUnitName', value: 'Dev' },
  { name: 'localityName', value: 'Beijing' },
  { name: 'stateOrProvinceName', value: 'Beijing' },
  { name: 'countryName', value: 'CN' }
];

cert.setSubject(attrs);
cert.setIssuer(attrs);
cert.setExtensions([
  { name: 'basicConstraints', cA: true },
  { name: 'keyUsage', keyCertSign: true, digitalSignature: true, nonRepudiation: true, keyEncipherment: true, dataEncipherment: true },
  { name: 'extKeyUsage', serverAuth: true, clientAuth: true, codeSigning: true, emailProtection: true, timeStamping: true },
  { name: 'subjectKeyIdentifier' }
]);

cert.sign(keys.privateKey, forge.md.sha256.create());
console.log('Certificate and key generated.');

// Create PKCS12 keystore
console.log('Creating PKCS12 keystore...');
const p12Asn1 = forge.pkcs12.toPkcs12Asn1(
  keys.privateKey,
  [cert],
  'lightning123',
  {
    algorithm: '3des',
    friendlyName: 'lightning',
    generateLocalKeyId: true,
    useMac: true
  }
);

const p12Der = forge.asn1.toDer(p12Asn1).getBytes();
const outputPath = path.join(__dirname, 'release.keystore');
fs.writeFileSync(outputPath, p12Der, 'binary');

console.log(`Keystore created at: ${outputPath}`);
console.log('Alias: lightning');
console.log('Password: lightning123');
