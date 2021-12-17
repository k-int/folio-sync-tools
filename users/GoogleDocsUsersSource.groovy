package folio.modrs.scripts

import mod_remote_sync.source.RemoteSyncActivity;
import mod_remote_sync.source.RecordSourceController;
import groovy.json.JsonOutput
import java.security.MessageDigest

public class GoogleDocsUsersSource implements RemoteSyncActivity {

  public void getNextBatch(String source_id,
                           Map state,
                           RecordSourceController rsc) {

    String identifierType=rsc.getAppSetting('usertools.url')

    def test_records = [
    ]

    test_records.each { Map testrec ->

       def license_json = JsonOutput.toJson(testrec);
       MessageDigest md5_digest = MessageDigest.getInstance("MD5");
       byte[] license_json_bytes = license_json.toString().getBytes()
       md5_digest.update(license_json_bytes);
       byte[] md5sum = md5_digest.digest();
       String license_hash = new BigInteger(1, md5sum).toString(16);

       rsc.upsertSourceRecord(source_id,
                              'TEST',
                              'TEST:LICENSE:'+testrec.id,
                              'TEST:LICENSE',
                              license_hash,
                              license_json_bytes);
    }

  }
}
