package folio.modrs.scripts

import mod_remote_sync.source.TransformProcess;
import mod_remote_sync.source.BaseTransformProcess;

import org.springframework.context.ApplicationContext
import groovy.util.logging.Slf4j
import mod_remote_sync.folio.FolioClient
import mod_remote_sync.folio.FolioClientImpl
import mod_remote_sync.PolicyHelperService
import mod_remote_sync.ResourceMappingService
import mod_remote_sync.ResourceMapping
import mod_remote_sync.FeedbackItem
import mod_remote_sync.ImportFeedbackService
import groovy.json.JsonSlurper
import com.k_int.web.toolkit.settings.AppSetting

@Slf4j
public class ProcessLaserLicense extends BaseTransformProcess implements TransformProcess {

  // TransformProcess now provides mappingCheck and  checkValueMapping as a base class service

  public static String MANUAL_POLICY_MESSAGE='The manual resource mapping policy applies - Operator needs to choose if the system should Create a new License, Map to an Existing one, or Ignore this license';

  public Map getMetadata() {
    return [
      version:'101'
    ]
  }

  public Map preflightCheck(String resource_id,
                            byte[] input_record,
                            ApplicationContext ctx,
                            Map local_context) {
    println("ProcessLaserLicense::preflightCheck(resource=${resource_id})");
    Map result = null;

    try {
      String folio_user = AppSetting.findByKey('laser.ermFOLIOUser')?.value;
      String folio_pass = AppSetting.findByKey('laser.ermFOLIOPass')?.value;
      String okapi_host = System.getenv('OKAPI_SERVICE_HOST') ?: 'okapi';
      String okapi_port = System.getenv('OKAPI_SERVICE_PORT') ?: '9130';
      String require_mapped_custprops = AppSetting.findByKey('mandatoryCustpropsMapping')?.value ?: 'yes';
      String require_mapped_refdata = AppSetting.findByKey('mandatoryRefdataMapping')?.value ?: 'yes';

      local_context.require_mapped_custprops = (require_mapped_custprops == 'yes' ? Boolean.True : Boolean.False )
      local_context.require_mapped_refdata = (require_mapped_refdata == 'yes' ? Boolean.True : Boolean.False )
      log.debug("user: ${folio_user},..., okapi_host:${okapi_host}, okapi_port:${okapi_port}");

      FolioClient fc = new FolioClientImpl(okapi_host, okapi_port, local_context.tenant, folio_user, folio_pass, 60000);
      fc.ensureLogin();

      local_context.folioClient = fc;

      // test source makes JSON records - so parse the byte array accordingly
      def jsonSlurper = new JsonSlurper()
      def parsed_record = jsonSlurper.parseText(new String(input_record))

      // Stash the parsed record so that we can use it in the process step without re-parsing if preflight passes
      local_context.parsed_record = parsed_record;

      local_context.processLog.add([ts:System.currentTimeMillis(), msg:"ProcessLaserLicense::preflightCheck(${resource_id},..) ${new Date()}"]);
      // local_context.processLog.add([ts:System.currentTimeMillis(), msg:parsed_record.toString()])

      ResourceMappingService rms = ctx.getBean('resourceMappingService');
      PolicyHelperService policyHelper = ctx.getBean('policyHelperService');
      ImportFeedbackService feedbackHelper = ctx.getBean('importFeedbackService');

      boolean pass = true;

      // Check to see if we already know about this license or if we should ask the user to create/map it
      pass &= mappingCheck(policyHelper,feedbackHelper,true,'LASER-LICENSE', resource_id, 'LASERIMPORT', 'FOLIO::LICENSE', local_context, parsed_record?.reference,
                           [ prompt:"Please indicate if the LASER License \"${parsed_record?.reference}\" with ID ${resource_id} should be mapped to an existing FOLIO License, a new FOLIO license created to track it, or the resorce should be ignored", folioResourceType:'license']);

      pass &= checkValueMapping(policyHelper,feedbackHelper,true,'LASER::LICENSE/STATUS', parsed_record.status, 'LASERIMPORT', 
                                'FOLIO::LICENSE/STATUS', local_context, parsed_record?.status,
                                [
                                  prompt:"Please provide a mapping for LASER License Status ${parsed_record.status}",
                                  subtype:'refdata',
                                  refdataUrl:'/licenses/refdata/status'
                                ]);

      String type_value = parsed_record.calculatedType ?: parsed_record.instanceOf.calculatedType ?: 'NO TYPE' 

      pass &= checkValueMapping(policyHelper,feedbackHelper,true,'LASER::LICENSE/TYPE', type_value, 'LASERIMPORT', 'FOLIO::LICENSE/TYPE', local_context, type_value,
                           [
                             prompt:"Please provide a mapping for LASER License Type ${type_value}",
                             subtype:'refdata',
                             refdataUrl:'/licenses/refdata/licenseType'
                           ]);

      pass &= preflightLicenseProperties(parsed_record, rms, policyHelper, feedbackHelper, local_context)

      result = [
        preflightStatus: pass ? 'PASS' : 'FAIL'
      ]

    }
    catch ( Exception e ) {
      e.printStackTrace();
      local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Problem in preflight ${e.message}"]);
      result = [
        preflightStatus: 'FAIL',
        log: [ code:'GENERAL-EXCEPTION',
               id:null,
               description:null,
               message: e.message ]
      ]
    }


    // We don't create licenses unless a user has told us to create a new one, or which license we should map
    // to. If we don't know - feedback that we need to know which license to map to, to create new, or not to map
    // If we know, the process can continue to process

    // manualCheckOnCreateResource()
    return result;
  }

  public Map process(String resource_id,
                     byte[] input_record,
                     ApplicationContext ctx,
                     Map local_context) {

    log.debug("ProcessLaserLicense::process(${resource_id},...)");

    def result = [
      processStatus:'FAIL'  // FAIL|COMPLETE
    ]


    local_context.processLog.add([ts:System.currentTimeMillis(), msg:"ProcessLaserLicense::process(${resource_id},..) ${new Date()}"]);

    try {

      ResourceMappingService rms = ctx.getBean('resourceMappingService');
      ImportFeedbackService feedbackHelper = ctx.getBean('importFeedbackService');

      def parsed_record = local_context.parsed_record

      // Lets see if we know about this resource already
      // These three parameters correlate with the first three parameters to policyHelper.manualResourceMapping in the preflight step
      ResourceMapping rm = rms.lookupMapping('LASER-LICENSE',parsed_record.globalUID,'LASERIMPORT');

      println("Load record : ${parsed_record}");

      if ( rm == null ) {

        println("No existing resource mapping found checking for feedback item");
        local_context.processLog.add([ts:System.currentTimeMillis(), msg:'License is new to FOLIO - check for create/update/ignore feedback'])

        // No existing mapping - see if we have a decision about creating or updating an existing record
        String feedback_correlation_id = "LASER-LICENSE:${resource_id}:LASERIMPORT:MANUAL-RESOURCE-MAPPING".toString()
        FeedbackItem fi = feedbackHelper.lookupFeedback(feedback_correlation_id)

        println("Got feedback: ${fi}");


        if ( fi != null ) {
          def answer = fi.parsedAnswer
          local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Applying located feedbacko ${answer}"])
          switch ( answer?.answerType ) {
            case 'create':
              createLicense(local_context.folioClient, rms, parsed_record, result, local_context);
              result.processStatus = 'COMPLETE'
              break;
            case 'ignore':
              println("Ignore ${resource_id} from LASER");
              result.processStatus = 'COMPLETE'
              break;
            case 'map':
              println("Import ${resource_id} as ${answer}");
              if ( answer?.mappedResource?.id ) {
                def resource_mapping = rms.registerMapping('LASER-LICENSE',parsed_record.globalUID, 'LASERIMPORT','M','LICENSES',answer?.mappedResource?.id);
                if ( resource_mapping ) {
                  result.resource_mapping = resource_mapping;
                  updateLicense(local_context.folioClient, resource_mapping.folioId,parsed_record,result)
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
      else {
        println("Got existing mapping... process ${rm}");
        local_context.processLog.add([ts:System.currentTimeMillis(), msg:"attempt update existing license mapping: ${rm}"]);
        updateLicense(local_context.folioClient, rm.folioId, parsed_record, result)
        result.processStatus = 'COMPLETE'
      }
    }
    catch ( Exception e ) {
      log.error("Exception in process",e);
      local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Problem in processing ${e.message}"]);
    }

    log.debug("ProcessLaserLicense complete / ${result.resource_mapping}");
    return result;
  }

  private void createLicense(FolioClient folioHelper, ResourceMappingService rms, Map laser_record, Map result, Map local_context) {

    // See https://gitlab.com/knowledge-integration/folio/middleware/folio-laser-erm-legacy/-/blob/master/spike/process.groovy#L207
    // See https://gitlab.com/knowledge-integration/folio/middleware/folio-laser-erm-legacy/-/blob/master/spike/FolioClient.groovy#L74
    log.debug("Create a new license");

    String type_value = laser_record.calculatedType ?: laser_record.instanceOf.calculatedType ?: 'NO TYPE' 
    String typeString =  getMappedValue(rms,'LASER::LICENSE/TYPE',type_value,'LASERIMPORT')
    String statusString =  getMappedValue(rms,'LASER::LICENSE/STATUS',laser_record.status,'LASERIMPORT')

    def requestBody = [
      name:laser_record?.reference,
      description: "Synchronized from LAS:eR license ${laser_record?.reference}/${laser_record?.globalUID} on ${new Date()}",
      type:typeString,
      customProperties: processLicenseProperties(rms,[:],laser_record,local_context),
      status:statusString,
      localReference: laser_record.globalUID,
      startDate: laser_record?.startDate,
      endDate: laser_record?.endDate
    ]

    def folio_license = folioHelper.okapiPost('/licenses/licenses', requestBody);
    local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Result of okapiPost /licenses/licenses: ${folio_license?.id}"]);

    if ( folio_license ) {
      // Grab the ID of our created license and use the resource mapping service to remember the correlation.
      // Next time we see resource_id as an ID of a LASER-LICENSE in the context of LASERIMPORT we will know that 
      // that resource maps to folio_licenses.id
      def resource_mapping = rms.registerMapping('LASER-LICENSE', laser_record.globalUID, 'LASERIMPORT','M','LICENSES',folio_license.id);
      result.processStatus = 'COMPLETE'
      // Send back the resource mapping so it can be stashed in the record
      log.debug("Create resource assigning resource mapping: ${resource_mapping}");
      result.resource_mapping = resource_mapping;
    }
    else {
      local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Post to licenses endpoint did not return a record"]);
    }
  }

  private void updateLicense(FolioClient folioHelper, String folio_license_id, Map laser_record, Map result) {
    log.debug("update existing license");
  }

  private boolean preflightLicenseProperties(Map laser_license,
                                             ResourceMappingService rms,
                                             PolicyHelperService policyHelper,
                                             ImportFeedbackService feedbackHelper,
                                             Map local_context) {
    boolean result = true;
    laser_license?.properties?.each { licprop ->
      log.debug("preflight laser license prop ${licprop}");
        def mapped_property = rms.lookupMapping('LASER::LICENSE/PROPERTY',licprop.token,'LASERIMPORT')
        if ( mapped_property != null ) {
          // We know about this license property - if it's refdata see if we know about the value mapping
          log.debug("Check license property value for ${licprop}");
          if ( licprop.type == 'Refdata' ) {
            result &= checkValueMapping(policyHelper,
                          feedbackHelper,false,"LASER::LICENSE/REFDATA/${licprop.refdataCategory}", licprop.value ?: 'NO-VALUE', 'LASERIMPORT', 
                             "FOLIO::LICENSE/REFDATA/${mapped_property.folioId}", 
                             local_context, licprop.value ?: 'NO-VALUE',
                             [prompt:"Map License refdata value ${licprop.refdataCategory}/${licprop.value?:'NO-VALUE'} - in target category ${mapped_property.folioId}",
                              subtype:"refdata",
                              type:"refdata"
                             ]);
          }
          // other types are Text and Date
        }
        else {
          // We've not seen this license property before - add it to the list of potentials
          result &= checkValueMapping(policyHelper,
                          feedbackHelper,false,'LASER::LICENSE/PROPERTY', licprop.token, 'LASERIMPORT', 'FOLIO::LICENSE/PROPERTY', local_context, licprop.token,
                             [prompt:"Map Optional License Property ${licprop.token}(${licprop.type})",
                              type:"refdata"
                             ]);
        }
    }
  }

  private Map processLicenseProperties(ResourceMappingService rms, Map folio_license, Map laser_license, Map local_context) {

    Map result = folio_license.customProperties ?: [:];

    laser_license?.properties?.each { licprop ->
      log.debug("Process license property : ${licprop}");
        String property_name = licprop.token

        def mapped_property = rms.lookupMapping('LASER::LICENSE/PROPERTY',licprop.token,'LASERIMPORT')

        if ( mapped_property != null ) {
          // See if we have a mapping for LASER::CUSTPROP/${licprop.token} 
          switch ( licprop.type ) {
            case 'Text':
              local_context.processLog.add([ts:System.currentTimeMillis(), msg:"adding text property: ${licprop.token}"]);
              result[mapped_property.folioId] = [
                note: noteParagraphJoiner(licprop.note, licprop.paragraph),
                value: licprop.value ?: 'NO-VALUE'
              ]
              break;
            case 'Date':
              local_context.processLog.add([ts:System.currentTimeMillis(), msg:"adding date property: ${licprop.token}"]);
              result[mapped_property.folioId] = [
                note: noteParagraphJoiner(licprop.note, licprop.paragraph),
                value: licprop.value
              ]
              break;
            case 'Refdata':
              def mapped_value = rms.lookupMapping("LASER::LICENSE/REFDATA/${licprop.refdataCategory}",licprop.value,'LASERIMPORT')
              local_context.processLog.add([ts:System.currentTimeMillis(), msg:"adding refdata property: ${licprop.token}:${licprop.value} mapped value ${mapped_value}"]);
              if ( mapped_value ) {
                result[mapped_property.folioId] = [
                  //  internal: internalValue,
                  note: noteParagraphJoiner(licprop.note, licprop.paragraph),
                  value: mapped_value.folioId,
                  type: 'com.k_int.web.toolkit.custprops.types.CustomPropertyRefdata'
                ]
              }
              break;
          }
        }
        else {
          // Skip any unmapped license property
          local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Skipping unmapped license property: ${licprop.token}"]);
        }
      // "note": "my test note",
      // "paragraph": "\u00a7 3 Abs. 1d:",
      // "refdataCategory": "permissions",
      // "scope": "License Property",
      // "isPublic": "Yes",
      // "type": "Refdata",
      // "type": "Text",
      // "value": "Prohibited (explicit)",
      // "token": "ILL electronic"
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
