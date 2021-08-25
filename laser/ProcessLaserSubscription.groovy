package folio.modrs.scripts

import mod_remote_sync.source.TransformProcess;

import org.springframework.context.ApplicationContext
import groovy.util.logging.Slf4j

@Slf4j
public class ProcessLaserSubscription implements TransformProcess {

  public Map preflightCheck(String resource_id,
                            byte[] input_record,
                            ApplicationContext ctx,
                            Map local_context) {

    boolean pass = false;

    try {
      // test source makes JSON records - so parse the byte array accordingly
      def jsonSlurper = new JsonSlurper()
      def parsed_record = jsonSlurper.parseText(new String(input_record))

      // Stash the parsed record so that we can use it in the process step without re-parsing if preflight passes
      local_context.parsed_record = parsed_record;

      local_context.processLog.add([ts:System.currentTimeMillis(), msg:"ProcessLaserSubscription::preflightCheck(${resource_id},..) ${new Date()}"]);

      pass=true
    }
    catch (Exception e) {
    }

    result = [
      preflightStatus: pass ? 'PASS' : 'FAIL'
    ]

    return result;
  }

  public Map process(String resource_id,
                     byte[] input_record,
                     ApplicationContext ctx,
                     Map local_context) {

    def folio_package_json = generateFOLIOPackageJSON('one',local_context.parsed_record);
    return [
      processStatus:'FAIL'   // FAIL|COMPLETE
    ]
  }

  private Map generateFOLIOPackageJSON(String generated_package_name, Map subscription) {
    def pkg_data = [
      "header": [
         "dataSchema": [
           "name": "mod-agreements-package",
           "version": "1.0"
         ],
         "trustedSourceTI": "true"
       ],
       "records": [
       ]
    ]


    // We build a "custom package" to describe the titles controlled by this subscription - because we want the FOLIO
    // agreement to only contain a package rather than indvidual entitlements (WHich would be much closer to the LAS:eR model)
    def content_items = [];

    // Add a record foe the package
    pkg_data.records.add([
      "source": "LAS:eR",
      "reference": subscription.globalUID,
      "name": generated_package_name,
      // "packageProvider": [
      //   "name": "provider"
      // ],
      "contentItems": content_items
    ])

    // For each package (So far only ever seen one, but hey)
    subscription.packages.each { pkg ->
      // Iterate over the titles in the package and add a content item for each one
      pkg.issueEntitlements.each { ie ->

      ArrayList coverage = buildCoverageStatements(ie.coverages);

        content_items.add([
          //"depth": null,
          "accessStart": dealWithLaserDate(ie.accessStartDate),
          "accessEnd": dealWithLaserDate(ie.accessEndDate),
          "coverage": coverage,
          "platformTitleInstance": [
            "platform": ie.tipp.platform.name,
            "platform_url": ie.tipp.platform.primaryUrl,
            "url": ie.tipp.hostPlatformURL,
            "titleInstance": [
              "name": ie.tipp.title.title,
              "identifiers":ie.tipp.title.identifiers,
              "type": ie.tipp.title.medium,
              "subtype": "electronic",
            ]
          ]
        ])
      }
    }

    // String pkg_json = JsonOutput.toJson(pkg_data);

    return pkg_data;
  }

  private ArrayList buildCoverageStatements(ArrayList ieCoverages) {
    ArrayList coverageStatements = []
    ieCoverages.each{ ieCoverage ->

      def startDate = dealWithLaserDate(ieCoverage.startDate)
      def endDate = dealWithLaserDate(ieCoverage.endDate)
      
      Map coverageStatement = [
        startDate: startDate,
        endDate: endDate,
        startVolume: ieCoverage.startVolume,
        endVolume: ieCoverage.endVolume,
        startIssue: ieCoverage.startIssue,
        endIssue: ieCoverage.endIssue
      ]
      coverageStatements << coverageStatement
    }

    return coverageStatements;
  }

  private String dealWithLaserDate(String laserDate) {
    String dateOutput = null
    if (laserDate != null) {
      try {
        LocalDateTime laserDateLocalDateTime = LocalDateTime.parse(laserDate)
        LocalDate laserDateLocalDate = laserDateLocalDateTime.toLocalDate()
        dateOutput = laserDateLocalDate.toString()
      } catch (Exception e) {
        println("Warning: failure parsing LAS:eR Date ${laserDate}: ${e.message}")
      }
    }
    return dateOutput;
  }
}
