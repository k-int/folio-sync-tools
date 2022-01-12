The certificates in this directory are for TESTING in a development environment only

https://pretagteam.com/question/codes-to-generate-a-public-key-in-an-elliptic-curve-algorithm-using-a-given-private-key
https://www.baeldung.com/java-read-pem-file-keys
http://lunar.lyris.com/help/Content/generating_public_and_private_keys.html


Make a new RSA Key : https://medium.com/@bn121rajesh/rsa-sign-and-verify-using-openssl-behind-the-scene-bf3cac0aade2

openssl genrsa -out myprivate.pem 512
openssl rsa -in myprivate.pem -pubout > mypublic.pem

Generate a pcks8 encoded private key
openssl pkcs8 -topk8 -nocrypt -in myprivate.pem -out myprivate.pcks8

