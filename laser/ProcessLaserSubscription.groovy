package folio.modrs.scripts

import mod_remote_sync.source.TransformProcess;
import mod_remote_sync.source.BaseTransformProcess;


import org.springframework.context.ApplicationContext
import groovy.util.logging.Slf4j
import groovy.json.JsonSlurper
import java.time.LocalDateTime
import java.time.LocalDate

import mod_remote_sync.PolicyHelperService
import mod_remote_sync.folio.FolioHelperService
import mod_remote_sync.folio.FolioClient
import mod_remote_sync.folio.FolioClientImpl
import mod_remote_sync.ResourceMappingService
import mod_remote_sync.ResourceMapping
import mod_remote_sync.FeedbackItem
import mod_remote_sync.ImportFeedbackService
import com.k_int.web.toolkit.settings.AppSetting
import com.k_int.web.toolkit.refdata.RefdataValue;

@Slf4j
public class ProcessLaserSubscription extends BaseTransformProcess implements TransformProcess {

  // see https://laser-dev.hbz-nrw.de/api/index

  private static String[] REQUIRED_PERMISSIONS = [
    'erm.packages.collection.import'
  ]

  public Map getMetadata() {
    return [
      version:'101'
    ]
  }

  public Map preflightCheck(String resource_id,
                            byte[] input_record,
                            ApplicationContext ctx,
                            Map local_context) {


    boolean pass = false;
    def result = [:]
    ResourceMappingService rms = ctx.getBean('resourceMappingService');
    PolicyHelperService policyHelper = ctx.getBean('policyHelperService');
    ImportFeedbackService feedbackHelper = ctx.getBean('importFeedbackService');

    try {
      String folio_user = AppSetting.findByKey('laser.ermFOLIOUser')?.value;
      String folio_pass = AppSetting.findByKey('laser.ermFOLIOPass')?.value;
      String okapi_host = System.getenv('OKAPI_SERVICE_HOST') ?: 'okapi';
      String okapi_port = System.getenv('OKAPI_SERVICE_PORT') ?: '9130';

      FolioClient fc = new FolioClientImpl(okapi_host, okapi_port, local_context.tenant, folio_user, folio_pass, 60000);
      fc.ensureLogin();

      if ( fc.checkPermissionGranted( REQUIRED_PERMISSIONS ) == false )
        throw new RuntimeException("Configured user is missing a required permission from the set ${REQUIRED_PERMISSIONS}")

      local_context.folioClient = fc;

      // test source makes JSON records - so parse the byte array accordingly
      def jsonSlurper = new JsonSlurper()
      def parsed_record = jsonSlurper.parseText(new String(input_record))
      log.info("Process subscription: ${parsed_record?.name}");

      // Stash the parsed record so that we can use it in the process step without re-parsing if preflight passes
      local_context.parsed_record = parsed_record;

      // LAS:eR subcriptions carry the license reference in license.globalUID
      // We will not try to process this subscription until the license sync task has created a record for the license
      // this sub depends on.
      if ( ( local_context.parsed_record?.licenses != null ) &&
           ( local_context.parsed_record?.licenses?.size() > 0 ) ) {
        String laser_license_guid = local_context.parsed_record?.licenses[0]?.globalUID;
        log.info("Try to look up laser license ${laser_license_guid}");
        if ( laser_license_guid != null ) {
          ResourceMapping license_rm = rms.lookupMapping('LASER-LICENSE', laser_license_guid, 'LASERIMPORT')
          if ( license_rm != null ) {
            local_context.folio_license_in_force = license_rm.folioId;
            log.debug("Located local laser license ${license_rm.folioId} for LASER license ${laser_license_guid}");
          }
          else {
            local_context.processLog.add([ts:System.currentTimeMillis(), msg:"No FOLIO license for LASER:${laser_license_guid}"]);
            log.warn("Unable to find local laser license for LASER license ${laser_license_guid}");
          }
        }
        else {
          local_context.processLog.add([ts:System.currentTimeMillis(), msg:"No LASER License referenced in sub - unable to continue"]);
        }
      }
      else {
        local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Source subscription does not seem to carry license data"]);
      }

      // We're passing everything - but not mapping licenses we don't know about
      pass=true

      // Users must select, for each subscription, if the incoming subscription should be matched to an existing agreement or if a new
      // agreement should be created to track that laser sub
      pass &= mappingCheck(policyHelper,feedbackHelper,true,'LASER-SUBSCRIPTION', resource_id, 'LASERIMPORT', 'FOLIO::AGREEMENT', local_context, parsed_record?.name,
                           [ prompt:"Please indicate if the LASER Subscription \"${parsed_record?.name}\" with ID ${parsed_record?.globalUID} should be mapped to an existing FOLIO Agreement, a new FOLIO Agreement created to track it, or the resorce should be ignored", folioResourceType:'agreement']);

      pass &= preflightSubscriptionProperties(parsed_record, rms, policyHelper, feedbackHelper, local_context)

      local_context.processLog.add([ts:System.currentTimeMillis(), msg:"ProcessLaserSubscription::preflightCheck(${resource_id},..) ${new Date()} result: ${pass}"]);
    }
    catch (Exception e) {
      local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Error in preflight: ${e.message}"]);
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

    Map result = [
      processStatus:'FAIL'   // FAIL|COMPLETE
    ]

    log.info("ProcessLaserSubscription::process(${resource_id})");
    local_context.processLog.add([ts:System.currentTimeMillis(), msg:"ProcessLaserSubscription::process(${resource_id})"]);

    String sync_titles = AppSetting.findByKey('laser.syncTitles')?.value
    String new_package_name = local_context.parsed_record.name;

    ResourceMappingService rms = ctx.getBean('resourceMappingService');
    ImportFeedbackService feedbackHelper = ctx.getBean('importFeedbackService');

    try {
      def package_details = null;

      if ( sync_titles?.equalsIgnoreCase('yes') ) {
        // Create or update the "custom package" representing the contents of this agreement
        def folio_package_json = generateFOLIOPackageJSON(new_package_name,local_context.parsed_record,local_context);

        if ( folio_package_json.records[0].contentItems?.size() > 0 ) {
          package_details = upsertPackage(folio_package_json, local_context.folioClient);
          local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Result of upsert custom package for sub: ${package_details}"]);
        }
        else {
          local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Found no items for package - skip package creation"]);
        }
      }
      else {
        local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Sync titles is off or unset - skip package creation (${sync_titles})"]);
      }

      def upsert_sub_result = upsertSubscription(rms,
                         local_context.folioClient,
                         '', // prefix
                         local_context,
                         local_context.parsed_record,
                         local_context.folio_license_in_force,
                         result,
                         feedbackHelper,
                         package_details?.packageId);

      if ( upsert_sub_result?.id != null ) {
        result.processStatus = 'COMPLETE'
        // result.resource_mapping = 
      }
      else {
        log.warn("Result of upsertSubscription: ${upsert_sub_result}");
      }

    }
    catch ( Exception e ) {
      local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Exception processing LASER subscription: ${e.message}"]);
      log.error("Exception processing LASER subscription",e);
    }

    return result
  }

  // private Map upsertPackage(Map folio_package_json, FolioHelperService folioHelper) {
  private Map upsertPackage(Map folio_package_json, FolioClient folioHelper) {
    // 1. see if we can locate an existing package with reference folio_package_json.reference (The laser UUID)
    // the /erm/packages/import endpoint automatically checks for an existing record with the given reference and updates
    // any existing package - perfect!
    log.debug("Attempt package upsert....");
    def import_response = folioHelper.okapiPost('/erm/packages/import', folio_package_json);
    log.debug("/erm/packages/import response: ${import_response}");
    return import_response;
  }

  private Map generateFOLIOPackageJSON(String generated_package_name, 
                                       Map subscription, 
                                       Map local_context) {

    local_context.processLog.add([ts:System.currentTimeMillis(), msg:"generateFOLIOPackageJSON(${generated_package_name},...) - laser reference: ${subscription.globalUID}. Package count is ${subscription.packages.size()}"]);

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

      local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Adding package..."]);

      // Iterate over the titles in the package and add a content item for each one
      pkg.issueEntitlements.each { ie ->

        ArrayList coverage = buildCoverageStatements(ie.coverages);

        local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Adding Title ${ie?.tipp?.title?.title}"]);

        content_items.add([
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

    // log.debug("Package data:${pkg_data}");
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


  // def lookupAgreement(String ref, FolioHelperService folioHelper) {
  def lookupAgreement(String ref, FolioClient folioHelper) {

    log.debug("lookupAgreement(${ref},...)");

    def result = null;

    def search_response = folioHelper.okapiGet('/erm/sas', [
        filters: "localReference==${ref}",
        perPage:10,
        sort:'localReference',
        term:ref,
        stats:true
      ]
    );

    log.debug("lookup agreement response totalRecords: ${search_response?.totalRecords}");

    if ( search_response ) {
      switch ( search_response?.totalRecords ) {
        case 0:
          result = null;
          break;
        case 1:
          result = search_response.results[0]
          break;
        default:
          throw new RuntimeException("Multiple subscriptions matched");
          break;
      }
    }
    else { 
      log.warn("No response to agreement lookup");
    }

    return result;
  }

  def upsertSubscription(ResourceMappingService rms,
                         FolioClient folioHelper,
                         String prefix, 
                         Map local_context,
                         Map subscription, 
                         String folio_license_id, 
                         Map processing_result,
                         ImportFeedbackService feedbackHelper,
                         String folio_pkg_id = null) {

    log.debug("upsertSubscription(...,prefix:${prefix},lic:${folio_license_id},pkg:${folio_pkg_id}...)");

    ResourceMapping rm = rms.lookupMapping('LASER-SUBSCRIPTION',subscription.globalUID,'LASERIMPORT');

    def result = [:];

    if ( rm != null ) {
      // def existing_subscription = lookupAgreement(subscription.globalUID, folioHelper)
      def existing_subscription = folioHelper.okapiGet('/erm/sas/'+rm.folioId,[:])

      if ( existing_subscription ) {
        log.debug("Result of GET /erm/sas/${rm.folioId}: sub with id ${existing_subscription?.id}");
    
        result = updateAgreement(rms,
                        local_context,
                        folioHelper, 
                        subscription, 
                        folio_license_id, 
                        folio_pkg_id, 
                        existing_subscription);
      }
      else {
        log.warn("Unable to locate named subscription");
        local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Unable to locate the nominated FOLIO agreement selected as the mapping for this sub"]);
      }
    }
    else {
      String feedback_correlation_id = "LASER-SUBSCRIPTION:LASER:SUB:${subscription.globalUID}:LASERIMPORT:MANUAL-RESOURCE-MAPPING".toString()
      FeedbackItem fi = feedbackHelper.lookupFeedback(feedback_correlation_id)

      if ( fi != null ) {

        def answer = fi.parsedAnswer
        log.debug("apply feedback ${answer}");

        local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Applying located feedbacko ${answer}"])
        switch ( answer?.answerType ) {
          case 'create':
              result = createAgreement(rms,
                      local_context,
                      folioHelper, 
                      subscription, 
                      folio_license_id, 
                      folio_pkg_id);
              if ( result && result.id != null ) {
                // Remember the ID of the agreement we are mapping this LASER subscription into
                def resource_mapping = rms.registerMapping('LASER-SUBSCRIPTION',subscription.globalUID, 'LASERIMPORT','M','AGREEMENTS',result.id);
              }
              result.processStatus = 'COMPLETE'
              break;
          case 'ignore':
              println("Ignore subscription ${subscription.globalUID} from LASER");
              result.processStatus = 'COMPLETE'
              break;
          case 'map':
            println("Import ${subscription.globalUID} as ${answer}");
            if ( answer?.mappedResource?.id ) {
              def resource_mapping = rms.registerMapping('LASER-SUBSCRIPTION',subscription.globalUID, 'LASERIMPORT','M','AGREEMENTS',answer?.mappedResource?.id);
              if ( resource_mapping ) { 
                // updateLicense(local_context.folioClient, resource_mapping.folioId,parsed_record,result)
                def existing_subscription = folioHelper.okapiGet('/erm/sas/'+resource_mapping.folioId, [:])
                result = updateAgreement(rms,
                                         local_context,
                                         folioHelper,
                                         subscription,
                                         folio_license_id,
                                         folio_pkg_id,
                                         existing_subscription)
                result.resource_mapping = resource_mapping;
                result.processStatus = 'COMPLETE'
              }
              else {
                local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Resource mapping step failed"]);
              }
            }
            else {
                local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Feedback to map existing is incomplete : ${answer}. Missing mappedResource.id"]);
            }
            break;
          default:
            println("Unhandled answer type: ${answer?.answerType}");
            break;
        }
      }
      else {
        local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Process blocked awaiting feedback with correlation id ${feedback_correlation_id}"]);
      }
    }
    return result;
  }

  def createAgreement(ResourceMappingService rms,
                      Map local_context,
                      FolioClient folioHelper,
                      Map subscription, 
                      String folio_license_id, 
                      String folio_pkg_id) {
    def result = [:];
    println("createAgreement(${subscription.name},${folio_license_id},${folio_pkg_id}...)");

    // We only add the custom package as an agreement line if the data from folio contained contentItems
    def items

    if (folio_pkg_id) {
      items = [
        [
          resource: [
            id: folio_pkg_id,
            authority: 'LASER',
            reference: "LASER:${subscription.globalUID}"
          ]
        ]
      ]
    }
    else {
      log.debug("No folio package found corresponding to laser sub.. Skip add content")
    }

    try {
      ArrayList periods = buildPeriodList(subscription, null, local_context)
      // Map statusMappings = pm.getAgreementStatusMap(subscription.status)
      // String statusString = statusMappings.get('agreement.status')
      String statusString = 'Draft';
      String reasonForClosure = null

      if (statusString == null) {
        // throw new Exception ("Mapping not found for LAS:eR status ${license.status}")
      }
      // reasonForClosure = statusMappings.get('agreement.reasonForClosure')

      def linked_licenses = null;
      if ( folio_license_id ) {
        linked_licenses = [
          [
            remoteId:folio_license_id,
            status:'controlling'
          ]
        ]
      }

      result = folioHelper.okapiPost('/erm/sas',
        [
          name:subscription.name,
          agreementStatus:statusString,
          reasonForClosure: reasonForClosure,
          description:"Created by remote-sync from LAS:eR on ${new Date()}",
          localReference: subscription.globalUID,
          periods: periods,
          linkedLicenses: linked_licenses,
          items: items,
          customProperties: processSubscriptionProperties(rms,[:],subscription,local_context)
        ]
      );

    } catch (Exception e) {
      log.error("Problem creating sub",e);
      local_context.processLog.add([ts:System.currentTimeMillis(), msg:"FATAL ERROR: Skipping agreement creation: ${e.message}"])
    }

    return result;
  }

  def updateAgreement(ResourceMappingService rms,
                      Map local_context,
                      FolioClient folioHelper,
                      Map subscription, 
                      String folio_license_id, 
                      String folio_pkg_id, 
                      Map folio_agreement) {

    String agreementId = folio_agreement.id
    def result = null;
    println("updateAgreement(name:${subscription.name},folio_license_id:${folio_license_id}...)");

    local_context.processLog.add([ts:System.currentTimeMillis(), msg:"update existing agreement: ${agreementId}"]);

    ArrayList linkedLicenses = []

    try {
      Map existing_controlling_license_data = lookupExistingAgreementControllingLicense(folio_agreement)
      println("Comparing license id: ${folio_license_id} to existing controlling license link: ${existing_controlling_license_data.existingLicenseId}")
      if ( ( existing_controlling_license_data == null ) ||
           ( existing_controlling_license_data.existingLicenseId != folio_license_id) ) {
        println("Existing controlling license differs from data harvested from LAS:eR--updating")
        linkedLicenses = []

        if ( existing_controlling_license_data.existingLicenseId != null ) {
          local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Controlling licnese has changed - unlinking ${existing_controlling_license_data.existingLinkId}"]);
          linkedLicenses.add ( [
            id: existing_controlling_license_data.existingLinkId,
            remoteId: existing_controlling_license_data.existingLicenseId,
            _delete: true
          ])
        }

        local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Adding controlling license: ${folio_license_id}"]);
        linkedLicenses.add(
          [
            remoteId:folio_license_id,
            status:'controlling'
          ] )
      } else {
        println("Existing controlling license matches data harvested from LAS:eR--moving on")
      }
    } catch (Exception e) {
      println("Warning: Cannot update controlling information for agreement: ${e.message}")
    }

    ArrayList periods = []

    try {
      // periods = buildPeriodList(subscription, folio_agreement, local_context)
      // II removing this for now - we don't want to always be nuking the periods we have attached 
      // we should compare and carefully merge the data rather than nuke and replace.
      //
      def period_for_sub = null; // folio_agreement.periods.find { ( ( it.startDate == subscription.startDate ) && ( it.endDate == subscription.endDate ) ) }
      folio_agreement.periods.each { agg_period ->
        log.debug("check period ${agg_period} for ${subscription.startDate}/${subscription.endDate}");
        if ( ( agg_period.startDate.equals(subscription.startDate) ) &&
             ( agg_period.endDate.equals(subscription.endDate ) ) ) {
          period_for_sub = agg_period;
        }
      }

      if ( period_for_sub == null ) {
        log.debug("Unable to locate period for agreement relating to sub - add one");
        // periods.add([ startDate: subscription.startDate, endDate: subscription.endDate ])
      }
    } catch (Exception e) {
      println("Warning: Cannot update period information for agreement: ${e.message}")
    }

    String statusString = null
    Map statusMappings = [:]
    try {
      statusMappings = null; // pm.getAgreementStatusMap(subscription.status)
      statusString = null; // statusMappings.get('agreement.status')

      if (statusString == null) {
        throw new Exception ("Mapping not found for LAS:eR status ${folio_agreement.status}")
      }
    } catch (Exception e) {
      println("Warning: Cannot update status information for agreement: ${e.message}")
    }

    // Look to see if there is an entitlement for the custom package relating to this subscription
    def items = folio_agreement.items;

    // reference: "LASER:${subscription.globalUID}"
    if ( folio_pkg_id != null ) {
      local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Checking for existing entitlement with reference: LASER.${subscription.globalUID}"]);
      def located_laser_entitlement = items.find { it.resource?.reference == "LASER:${subscription.globalUID}".toString() }
      if ( located_laser_entitlement != null ) {
        local_context.processLog.add([ts:System.currentTimeMillis(), msg: "Located entitlement for custom package with reference LASER:${subscription.globalUID} - folio package is ${folio_pkg_id}"])
      }
      else {
        local_context.processLog.add([ts:System.currentTimeMillis(), msg: "Unable to locate agreement line for LASER:${subscription.globalUID}/${folio_pkg_id} - add it"]);

        items.add (
          [
            resource: [
              id: folio_pkg_id,
              authority: 'LASER',
              reference: "LASER:${subscription.globalUID}"
            ]
          ]
        );
      }
    }
    else {
      local_context.processLog.add([ts:System.currentTimeMillis(), msg: "No local package - is sync titles switched on?"]);
    }

    // items should contain a resource which includes folio_pkg_id

    Map requestBody = [
      'name': subscription.name,
      'linkedLicenses': linkedLicenses,
      'description': "${folio_agreement.description?:''}\nUpdated by remote-sync from LAS:eR on ${new Date()}",
      'periods': periods,
      'items': items
    ]

    if (statusString != null) {
      requestBody["agreementStatus"] = statusString
      requestBody["reasonForClosure"] = null // statusMappings.get('agreement.reasonForClosure')
    }

    result = folioHelper.okapiPut("/erm/sas/${agreementId}", requestBody);

    return result;
  }

  public ArrayList buildPeriodList(Map subscription, Map folioAgreement, Map local_context) {

    local_context.processLog.add([ts:System.currentTimeMillis(), msg: "buildPeriodList ::${subscription.globalUID} / laser_start:${subscription.startDate} laser_end:${subscription.endDate}"])

    if (subscription.startDate == null) {
      throw new RuntimeException ("There is no startDate for this subscription")
    }

    ArrayList periodList = []

    if (folioAgreement) {
      // We already have an agreement, so the period will need updating
      if ( ( folioAgreement.periods ) && 
           ( folioAgreement.periods?.size() > 0 ) ) {
        Map deleteMap = [
          id: folioAgreement.periods?.getAt(0)?.id,
          _delete: true
        ]
        periodList.add(deleteMap)
      }
    }

    Map newPeriod = [
      startDate: subscription.startDate,
      endDate: subscription.endDate
    ]

    periodList.add(newPeriod)

    return periodList;
  }

  // Returns the license link id of the current controlling license
  public Map lookupExistingAgreementControllingLicense(Map folio_agreement) {

    Map result = [:]

    ArrayList linkedLicenses = [] // folio_agreement.linkedLicenses
    linkedLicenses = folio_agreement?.linkedLicenses?.find { obj.status?.value == 'controlling' };
    if ( linkedLicenses == null ) 
      linkedLicenses=[];

    // The below should always go smoothly, since FOLIO only allows a single controlling license. If this fails then something hasd gone wrong internally in FOLIO
    switch ( linkedLicenses.size() ) {
      case 0:
        result = [existingLinkId: null, existingLicenseId: null];
        break;
      case 1:
        result = [existingLinkId: linkedLicenses[0].id, existingLicenseId: linkedLicenses[0].remoteId]
        break;
      default:
        throw new RuntimeException("Multiple agreement controlling licenses found/2 (${linkedLicenses.size()})");
        break;
    }
    return result;
  }

  private boolean preflightSubscriptionProperties(Map laser_subscription,
                                                  ResourceMappingService rms,
                                                  PolicyHelperService policyHelper,
                                                  ImportFeedbackService feedbackHelper,
                                                  Map local_context) {
    boolean result = true;
    laser_subscription?.properties?.each { subprop ->
      log.debug("preflight laser subscription prop ${subprop}");
      if ( subprop.value != null ) {
        def mapped_property = rms.lookupMapping('LASER::SUBSCRIPTION/PROPERTY',subprop.token,'LASERIMPORT')
        if ( mapped_property != null ) {
          // We know about this subscription property - if it's refdata see if we know about the value mapping
          log.debug("Check subscription property value for ${subprop}");
          if ( subprop.type == 'Refdata' ) {
            result &= checkValueMapping(policyHelper,
                          feedbackHelper,false,"LASER::SUBSCRIPTION/REFDATA/${subprop.refdataCategory}", subprop.value, 'LASERIMPORT',
                             "FOLIO::SUBSCRIPTION/REFDATA/${mapped_property.folioId}",
                             local_context, subprop.value,
                             [prompt:"Map License refdata value ${subprop.refdataCategory}/${subprop.value} - in target category ${mapped_property.folioId}",
                              subtype:"refdata",
                              type:"refdata"
                             ]);
          }
          // other types are Text and Date
        }
        else {
          // We've not seen this subscription property before - add it to the list of potentials
          result &= checkValueMapping(policyHelper,
                          feedbackHelper,false,'LASER::SUBSCRIPTION/PROPERTY', subprop.token, 'LASERIMPORT', 'FOLIO::SUBSCRIPTION/PROPERTY', local_context, subprop.token,
                             [prompt:"Map Optional Subscription Property ${subprop.token}(${subprop.type})",
                              type:"refdata"
                             ]);
        }
      }
      else {
        // Skipping NULL subscription property value
      }
    }
    
    local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Preflight subscription properties: ${result}"]);

    return result
  }

  private Map processSubscriptionProperties(ResourceMappingService rms, Map folio_subscription, Map laser_subscription, Map local_context) {

    Map result = folio_subscription.customProperties ?: [:];

    laser_subscription?.properties?.each { subprop ->
      log.debug("Process subscription property : ${subprop}");
      if ( subprop.value != null ) {
        String property_name = subprop.token

        def mapped_property = rms.lookupMapping('LASER::SUBSCRIPTION/PROPERTY',subprop.token,'LASERIMPORT')

        if ( mapped_property != null ) {
          switch ( subprop.type ) {
            case 'Text':
              local_context.processLog.add([ts:System.currentTimeMillis(), msg:"adding text property: ${subprop.token}"]);
              result[mapped_property.folioId] = [
                note: noteParagraphJoiner(subprop.note, subprop.paragraph),
                value: subprop.value
              ]
              break;
            case 'Date':
              local_context.processLog.add([ts:System.currentTimeMillis(), msg:"adding date property: ${subprop.token}"]);
              result[mapped_property.folioId] = [
                note: noteParagraphJoiner(subprop.note, subprop.paragraph),
                value: subprop.value
              ]
              break;
            case 'Refdata':
              def mapped_value = rms.lookupMapping("LASER::SUBSCRIPTION/REFDATA/${subprop.refdataCategory}",subprop.value,'LASERIMPORT')
              local_context.processLog.add([ts:System.currentTimeMillis(), msg:"adding refdata property: ${subprop.token}:${subprop.value} mapped value ${mapped_value}"]);
              if ( mapped_value ) {
                result[mapped_property.folioId] = [
                  //  internal: internalValue,
                  note: noteParagraphJoiner(subprop.note, subprop.paragraph),
                  value: mapped_value.folioId,
                  type: 'com.k_int.web.toolkit.custprops.types.CustomPropertyRefdata'
                ]
              }
              break;
          }
        }
        else {
          // Skip any unmapped license property
          local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Skipping unmapped license property: ${subprop.token}"]);
        }
      }
    }

    return result;
  }

  public String noteParagraphJoiner(String note, String paragraph) {
    // The paragraph information for custom properties will for now be stored alongside the note in FOLIO's internalNote field, with a delimiter defined below
    String delimiter = " :: "

    if (note != null && paragraph != null) {
      return note << delimiter << paragraph;
    } else if (note == null && paragraph == null) {
      return null;
    } else if (note == null) {
      return paragraph;
    } else {
      return note;
    }
  }

}
