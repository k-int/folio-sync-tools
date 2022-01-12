The certificates in this directory are for automated TESTING in a CI/CD environment only - they are not used in any public facing systems

In order to generate config for this - the groovy file expects a file in ~/.config/remote-sync-signing. That file should have the form
    [default]
    private_key=/full/path/to/private/as/pcks8/eg/mypublic.pem
    public_key=/full/path/to/private/as/pem/eg/myprivate.pcks8

To actually make these keys using openssl you can use the following

openssl genrsa -out myprivate.pem 512
openssl rsa -in myprivate.pem -pubout > mypublic.pem

Generate a pcks8 encoded private key
openssl pkcs8 -topk8 -nocrypt -in myprivate.pem -out myprivate.pcks8

