"""Emit the client type contract. Run python contracts/generate_openapi.py."""
import json
from pathlib import Path

def obj(fields):
    props, required = {}, []
    for name, spec in fields.items():
        optional = name.endswith('?')
        name = name.rstrip('?')
        if isinstance(spec, str):
            nullable = spec.endswith('?')
            spec = spec.rstrip('?')
            if spec.endswith('[]'):
                value = {'type':'array','items':kind(spec[:-2])}
            else:
                value = kind(spec)
            if nullable:
                value = {'allOf':[value], 'nullable':True} if '$ref' in value else dict(value, nullable=True)
        else:
            value = spec
        props[name] = value
        if not optional:
            required.append(name)
    return {'type':'object','properties':props,'required':required}

def kind(spec):
    if spec in ('string','boolean','integer','number'):
        return {'type':spec}
    return {'$ref':'#/components/schemas/'+spec}

def enum(*values):
    return {'type':'string','enum':list(values)}

schemas = {
 'Role':enum('STUDENT','MANAGER'),
 'RepairStatus':enum('SUBMITTED','ACCEPTED','PROCESSING','PENDING_CONFIRMATION','COMPLETED','CANCELLED','REJECTED'),
 'Category':enum('UTILITIES','FURNITURE','NETWORK','OTHER'),
 'Priority':enum('NORMAL','URGENT'),
 'Grade':enum('EXCELLENT','GOOD','PASS','RECTIFY'),
 'Building':obj(dict(id='string',code='string',name='string',floorCount='integer',enabled='boolean',version='integer')),
 'Room':obj(dict(id='string',buildingId='string',buildingName='string',roomNo='string',floorNo='integer',capacity='integer',occupiedCount='integer',enabled='boolean',remark='string',version='integer')),
 'Student':obj(dict(id='string',userId='string',studentNo='string',name='string',college='string',major='string',grade='string',className='string',phone='string',status='string',roomId='string?',roomNo='string?',buildingName='string?',version='integer')),
 'Residence':obj(dict(id='string',studentId='string',studentName='string',studentNo='string',roomId='string',roomNo='string',buildingId='string',buildingName='string',checkedInAt='string',checkedOutAt='string?',reason='string',version='integer')),
 'FileAsset':obj(dict(id='string',originalName='string',mime='string',sizeBytes='integer',url='string',thumbnailUrl='string',usage='string')),
 'RepairEvent':obj(dict(id='string',orderId='string',fromStatus='string?',toStatus='RepairStatus',action='string',operatorName='string',note='string',createdAt='string')),
 'Repair':obj(dict(id='string',orderNo='string',reporterStudentId='string',reporterName='string',roomId='string',roomNo='string',buildingId='string',buildingName='string',title='string',description='string',category='Category',priority='Priority',urgentReason='string',contactName='string',contactPhone='string',contactTimeSlot='string',status='RepairStatus',handlerUserId='string?',handlerName='string?',relatedOrderId='string?',submittedAt='string',completedAt='string?',version='integer',allowedActions='string[]',reportImages='FileAsset[]',resultImages='FileAsset[]',latestResult='string?')),
 'Visitor':obj(dict(id='string',visitorName='string',phone='string',visitedStudentId='string',studentName='string',roomId='string',roomNo='string',buildingId='string',buildingName='string',purpose='string',remark='string',arrivedAt='string',leftAt='string?',status=enum('VISITING','LEFT'),version='integer')),
 'Hygiene':obj(dict(id='string',roomId='string',roomNo='string',buildingId='string',buildingName='string',inspectionDate='string',round='string',inspectedAt='string',floorScore='integer',deskScore='integer',bedScore='integer',bathroomScore='integer',totalScore='integer',grade='Grade',issueNote='string',remediationNote='string',inspectorName='string',relatedInspectionId='string?',voidedAt='string?',version='integer',images='FileAsset[]')),
 'Overview':obj(dict(roomCount='integer',capacity='integer',occupiedCount='integer',availableCount='integer',occupancyRate='number',openRepairs='integer',pendingConfirmation='integer',visitingCount='integer',todayVisitors='integer',hygieneAverage='number?',rectifyRoomCount='integer')),
 'User':obj(dict(id='string',username='string',displayName='string',role='Role',buildingIds='string[]',studentId='string?',currentRoom='Room?',**{'mustChangePassword?':'boolean'})),
 'LoginRequest':obj(dict(username='string',password='string',clientType=enum('WEB','MOBILE'))),
 'LoginResult':obj({'user':'User','token?':'string'}),
 'PasswordRequest':obj(dict(oldPassword='string',newPassword='string')),
 'LeaveRequest':obj({'expectedVersion':'integer','leftAt?':'string'}),
 'ContactResult':obj(dict(phone='string')),
 'Csrf':obj(dict(token='string',headerName='string')),
 'RoomRequest':obj({'buildingId':'string','roomNo':'string','floorNo':'integer','capacity':'integer','enabled?':'boolean','remark?':'string','expectedVersion?':'integer'}),
 'StudentRequest':obj({'studentNo':'string','name':'string','college':'string','major?':'string','grade?':'string','className?':'string','phone':'string','status?':'string','expectedVersion?':'integer'}),
 'RepairRequest':obj({'title':'string','description':'string','category':'Category','priority':'Priority','urgentReason?':'string','contactName':'string','contactPhone':'string','contactTimeSlot?':'string','reportImageIds':'string[]','relatedOrderId?':'string?'}),
 'ActionRequest':obj({'expectedVersion':'integer','reason?':'string','note?':'string','resultDescription?':'string','resultImageIds?':'string[]'}),
 'VisitorRequest':obj({'visitorName':'string','phone':'string','visitedStudentId':'string','purpose':'string','remark?':'string','arrivedAt?':'string','confirmDuplicate?':'boolean','expectedVersion?':'integer','correctionReason?':'string','leftAt?':'string'}),
 'HygieneRequest':obj({'roomId':'string','inspectionDate':'string','round':'string','inspectedAt?':'string','floorScore':'integer','deskScore':'integer','bedScore':'integer','bathroomScore':'integer','issueNote?':'string','remediationNote?':'string','relatedInspectionId?':'string?','imageIds':'string[]','expectedVersion?':'integer','correctionReason?':'string'}),
 'AiRequest':obj(dict(description='string',imageIds='string[]',consentToExternalAnalysis='boolean')),
 'AiSuggestion':obj(dict(suggestionId='string',title='string',category='Category',summary='string',uncertainty='string',questions='string[]',riskTags='string[]',requiresHumanReview='boolean')),
 'NamedCount':obj(dict(name='string',value='number')),
 'TrendCount':obj(dict(date='string',count='integer')),
 'TrendAverage':obj(dict(date='string',average='number')),
 'RepairStatistics':obj(dict(byStatus='NamedCount[]',byCategory='NamedCount[]',trend='TrendCount[]',averageHours='number?',total='integer',completed='integer')),
 'HygieneStatistics':obj(dict(average='number?',byGrade='NamedCount[]',trend='TrendAverage[]',rectifyRoomCount='integer')),
 'VisitorStatistics':obj(dict(visitingCount='integer',total='integer',trend='TrendCount[]')),
 'CorrectionEvent':obj(dict(id='string',action='string',operatorName='string',note='string',changes='string',createdAt='string')),
 'Error':obj(dict(error=obj(dict(code='string',message='string',fieldErrors={'type':'array','items':{'type':'object'}})),traceId='string')),
}
for name in ('Building','Room','Student','Residence','Repair','Visitor','Hygiene'):
    schemas[name+'Page']=obj(dict(items=name+'[]',total='integer',page='integer',pageSize='integer'))

# Dispatch is a separate read model; route recommendations do not replace repair lifecycle states.
visit_fields=dict(windowStart='string?',windowEnd='string?',windowConfirmed='boolean',estimatedMinutes='integer',visitRequired='boolean',waitingReason='string')
schemas['Repair']['properties'].update(obj(visit_fields)['properties'])
schemas['Repair']['required'].extend(visit_fields)
schemas['RepairRequest']['properties'].update(obj({'windowStart?':'string?','windowEnd?':'string?'})['properties'])
schemas.update({
 'DispatchTaskState':enum('RECOMMENDED','ASSIGNED','EN_ROUTE','ON_SITE','BLOCKED'),
 'DispatchManager':obj(dict(userId='string',displayName='string',online='boolean',acceptingNewTasks='boolean',buildingIds='string[]',positionBuildingId='string?',positionBuildingName='string?',positionFloorNo='integer?',positionUpdatedAt='string?',availableUntil='string?',version='integer')),
 'DispatchTask':obj(dict(orderId='string',orderNo='string',title='string',description='string',buildingId='string',buildingName='string',roomId='string',roomNo='string',floorNo='integer',priority='Priority',repairStatus='RepairStatus',handlerUserId='string?',managerUserId='string?',state='DispatchTaskState',sequence='integer',locked='boolean',estimatedArrival='string?',estimatedFinish='string?',travelMinutes='integer',serviceMinutes='integer',windowStart='string?',windowEnd='string?',windowConfirmed='boolean',version='integer',orderVersion='integer',reason='string')),
 'DispatchRoute':obj(dict(manager='DispatchManager',tasks='DispatchTask[]',travelMinutes='integer',waitingMinutes='integer',workloadMinutes='integer')),
 'DispatchUnassigned':obj(dict(task='DispatchTask',reasonCode='string',reason='string',candidateCount='integer')),
 'DispatchMetrics':obj(dict(totalTasks='integer',assignedCount='integer',unassignedCount='integer',travelMinutes='integer',baselineTravelMinutes='integer',maxWorkloadMinutes='integer')),
 'DispatchBoard':obj(dict(revision='integer',generatedAt='string',dirty='boolean',onlineCount='integer',participatingCount='integer',me='DispatchManager',routes='DispatchRoute[]',unassigned='DispatchUnassigned[]',metrics='DispatchMetrics',changes='string[]')),
 'DispatchHeartbeatRequest':obj({}),
 'DispatchSettingsRequest':obj({'acceptingNewTasks':'boolean','availableUntil?':'string?','positionBuildingId?':'string?','positionFloorNo?':'integer?','expectedVersion':'integer'}),
 'DispatchReplanRequest':obj({'expectedRevision?':'integer'}),
 'DispatchClaimRequest':obj({'expectedOrderVersion':'integer','expectedRevision?':'integer'}),
 'DispatchTaskActionRequest':obj({'expectedTaskVersion':'integer','reason?':'string'}),
 'DispatchHandoffRequest':obj(dict(targetManagerId='string',reason='string',expectedOrderVersion='integer',expectedTaskVersion='integer')),
 'DispatchTakeoverRequest':obj(dict(reason='string',expectedOrderVersion='integer',expectedTaskVersion='integer')),
 'RepairVisitRequest':obj({'expectedVersion':'integer','windowStart?':'string?','windowEnd?':'string?','windowConfirmed':'boolean','estimatedMinutes?':'integer','visitRequired':'boolean','waitingReason?':'string'}),
 'DispatchTravelLink':obj(dict(fromBuildingId='string',toBuildingId='string',minutes='integer',estimated='boolean',version='integer')),
 'DispatchTravel':obj(dict(floorMinutes='integer',sameFloorMinutes='integer',links='DispatchTravelLink[]')),
 'DispatchTravelRequest':obj(dict(minutes='integer',expectedVersion='integer')),
 'ManagerAssistRequest':obj({'consentToExternalAnalysis':'boolean','includeImages?':'boolean'}),
 'AssistEvidence':obj(dict(orderId='string',orderNo='string',title='string',status='RepairStatus')),
 'AssistFact':obj(dict(label='string',value='string')),
 'ManagerAdvice':obj(dict(suggestionId='string',summary='string',findings='string[]',suggestedActions='string[]',questions='string[]',uncertainty='string',evidence='AssistEvidence[]',facts='AssistFact[]',generatedAt='string',requiresHumanReview='boolean')),
})

# Device observations are ephemeral evidence, separate from repair lifecycle facts.
schemas.update({
 'MobilitySessionState':enum('ACTIVE','PAUSED','ENDED','EXPIRED'),
 'MobilityStatus':enum('VALID','STALE','UNKNOWN'),
 'MobilitySource':enum('MANUAL','QR','GPS','NONE'),
 'MobilityMotion':enum('STILL','WALKING','UNKNOWN'),
 'MobilityQuality':enum('LOW','MEDIUM','HIGH'),
 'MobilitySession':obj(dict(id='string',userId='string',state='MobilitySessionState',sourceEpoch='integer',leaseExpiresAt='string',startedAt='string',lastSampleAt='string?',backgroundRequested='boolean',version='integer')),
 'MobilityContext':obj(dict(userId='string',sessionId='string?',status='MobilityStatus',source='MobilitySource',buildingId='string?',buildingName='string?',floorNo='integer?',latitude='number?',longitude='number?',accuracyMeters='number?',speedMps='number?',motion='MobilityMotion',quality='MobilityQuality',observedAt='string?',expiresAt='string?',contextVersion='integer',explanation='string')),
 'CampusAnchor':obj(dict(id='string',buildingId='string',buildingName='string',floorNo='integer?',name='string',scanCode='string',latitude='number?',longitude='number?',radiusMeters='number?',enabled='boolean',version='integer')),
 'CampusAnchors':obj(dict(items='CampusAnchor[]')),
 'MobilityPolicy':obj(dict(consentVersion='string',maxBatchSize='integer',maxBodyBytes='integer',reportIntervalSeconds='integer',contextTtlSeconds='integer',leaseSeconds='integer')),
 'MobilityView':obj(dict(session='MobilitySession?',context='MobilityContext?',anchors='CampusAnchor[]',policy='MobilityPolicy')),
 'MobilityStartRequest':obj({'clientInstanceId':'string','consentVersion':'string','backgroundRequested?':'boolean','takeover?':'boolean','expectedVersion?':'integer'}),
 'MobilityActionRequest':obj(dict(sourceEpoch='integer',expectedVersion='integer')),
 'MobilitySample':obj({'sequence':'integer','streamId':'string','observedAt':'string','elapsedMs':'number','latitude?':'number','longitude?':'number','accuracyMeters?':'number','speedMps?':'number','speedAccuracyMps?':'number','stepDelta?':'integer','motion':'MobilityMotion','clientQuality?':'MobilityQuality'}),
 'MobilityBatchRequest':obj(dict(sourceEpoch='integer',batchId='string',samples='MobilitySample[]')),
 'MobilityBatchAck':obj(dict(batchId='string',acceptedCount='integer',rejectedCount='integer',lastSequence='integer',context='MobilityContext?')),
 'MobilityAnchorRequest':obj({'sourceEpoch':'integer','anchorId':'string','source':enum('MANUAL','QR'),'expectedContextVersion':'integer'}),
 'CampusAnchorRequest':obj({'buildingId':'string','name':'string','floorNo?':'integer?','latitude?':'number?','longitude?':'number?','radiusMeters?':'number?','enabled?':'boolean','expectedVersion?':'integer'}),
 'MobilityTravelModel':obj(dict(fromBuildingId='string',toBuildingId='string',minutes='integer',sampleCount='integer',sessionCount='integer',enabled='boolean',version='integer',updatedAt='string')),
 'MobilityTravelModels':obj(dict(items='MobilityTravelModel[]')),
 'MobilityModelRollbackRequest':obj(dict(expectedVersion='integer')),
 'MobilityEfficiency':obj(dict(sampleCount='integer',measuredCount='integer',meanAbsoluteErrorMinutes='number?',predictedMinutes='number?',actualMinutes='number?',personalModelCount='integer',explanation='string')),
})
schemas['MobilityBatchRequest']['properties']['samples'].update(minItems=1,maxItems=20)
schemas['MobilitySample']['properties']['sequence']['minimum']=1
schemas['MobilitySample']['properties']['elapsedMs']['minimum']=0
schemas['MobilitySample']['properties']['streamId'].update(minLength=1,maxLength=80)
schemas['MobilityBatchRequest']['properties']['batchId'].update(minLength=1,maxLength=80)
schemas['MobilityStartRequest']['properties']['clientInstanceId'].update(minLength=1,maxLength=80)
schemas['CampusAnchor']['description']='Scan payload is dormlink-anchor:<scanCode>. Static codes require user confirmation and server authorization; they are not proof of physical presence.'
schemas['MobilityContext']['description']='Current own-account context, never a raw track. Coordinates expire with the working session. GPS cannot establish a floor or room.'

# Persistent service follow-up: notifications are an authenticated inbox, not remote push.
schemas.update({
 'ServiceMessage':obj(dict(id='string',kind='string',title='string',body='string',businessType='string',businessId='string',createdAt='string',readAt='string?',version='integer')),
 'ServiceMessagePage':obj(dict(items='ServiceMessage[]',total='integer',page='integer',pageSize='integer')),
 'ServiceMessageSummary':obj(dict(unreadCount='integer',deliveryPending='integer',deliveryFailed='integer')),
 'ServiceMessageReadRequest':obj(dict(expectedVersion='integer')),
 'RemediationStatus':enum('OPEN','SUBMITTED','CLOSED','CANCELLED'),
 'Remediation':obj(dict(id='string',inspectionId='string',roomId='string',roomNo='string',buildingId='string',buildingName='string',responsibleStudentId='string',responsibleName='string',issueNote='string',dueAt='string',status='RemediationStatus',feedbackNote='string',reviewNote='string',relatedRecheckId='string?',createdAt='string',submittedAt='string?',closedAt='string?',version='integer',overdue='boolean',evidenceImages='FileAsset[]')),
 'RemediationPage':obj(dict(items='Remediation[]',total='integer',page='integer',pageSize='integer')),
 'RemediationRequest':obj(dict(inspectionId='string',responsibleStudentId='string',dueAt='string',issueNote='string')),
 'RemediationAssignmentRequest':obj(dict(expectedVersion='integer',responsibleStudentId='string',dueAt='string',reason='string')),
 'RemediationCancelRequest':obj(dict(expectedVersion='integer',reason='string')),
 'RemediationFeedbackRequest':obj(dict(expectedVersion='integer',feedbackNote='string',imageIds='string[]')),
 'RemediationReviewRequest':obj({'expectedVersion':'integer','approved':'boolean','reviewNote':'string','relatedRecheckId?':'string'}),
 'AssistFeedback':obj(dict(id='string',orderId='string',suggestionId='string',decision=enum('ADOPTED','MODIFIED','IGNORED'),reason='string',outcome='string',operatorName='string',createdAt='string',canFollowUp='boolean')),
 'AssistFeedbackList':obj(dict(items='AssistFeedback[]')),
 'AssistFeedbackRequest':obj(dict(suggestionId='string',decision=enum('ADOPTED','MODIFIED','IGNORED'),reason='string',outcome='string')),
})

paths={}
def add(path, method, output=None, body=None, page=False, binary=False):
    target = {'type':'string','format':'binary'} if binary else (kind(output) if output else {'type':'object','additionalProperties':True})
    result = target if binary else obj(dict(data=target,traceId='string'))
    op={'operationId':method+'_'+path.strip('/').replace('/','_').replace('{','').replace('}','').replace('-','_'), 'responses':{'200':{'description':'Success','content':{('image/png' if binary else 'application/json'):{'schema':result}}},'400':{'description':'Error','content':{'application/json':{'schema':kind('Error')}}}},'parameters':[]}
    for part in path.split('/'):
        if part.startswith('{'):
            op['parameters'].append({'name':part[1:-1],'in':'path','required':True,'schema':{'type':'string'}})
    if method=='get' and page:
        for field in ('page','pageSize','q','buildingId','roomId','studentId','floorNo','enabled','occupancy','status','startDate','endDate','category','grade','round','inspectionDate'):
            op['parameters'].append({'name':field,'in':'query','schema':{'type':'integer' if field in ('page','pageSize') else 'string'}})
    if method in ('post','patch','delete'):
        op['parameters'].append({'name':'Idempotency-Key','in':'header','required':False,'schema':{'type':'string'}})
    if body:
        op['requestBody']={'required':True,'content':{'application/json':{'schema':kind(body) if isinstance(body,str) else body}}}
    paths.setdefault(path,{})[method]=op

add('/auth/csrf','get','Csrf');add('/auth/login','post','LoginResult','LoginRequest');add('/auth/me','get','User');add('/auth/logout','post');add('/auth/change-password','post',body=schemas['PasswordRequest'])
for path, entity, req in [('buildings','Building',None),('rooms','Room','RoomRequest'),('students','Student','StudentRequest'),('repairs','Repair','RepairRequest'),('visitors','Visitor','VisitorRequest'),('hygiene-inspections','Hygiene','HygieneRequest')]:
    add('/'+path,'get',entity+'Page',page=True);add('/'+path+'/{id}','get',entity);add('/'+path,'post',entity,req or obj({'code':'string','name':'string','floorCount':'integer','enabled?':'boolean'}))
    if path!='repairs': add('/'+path+'/{id}','patch',entity,req or obj({'code':'string','name':'string','floorCount':'integer','enabled?':'boolean','expectedVersion':'integer'}))
add('/residences','get','ResidencePage',page=True);add('/me/residences','get','ResidencePage',page=True);add('/me/room','get','Room');add('/me/hygiene-inspections','get','HygienePage',page=True)
paths['/me/room']['get']['responses']['200']['content']['application/json']['schema']=obj(dict(data='Room?',traceId='string'))
add('/residences/check-in','post','Residence',obj({'studentId':'string','roomId':'string','checkedInAt?':'string'}))
add('/residences/{id}/transfer','post','Residence',obj(dict(targetRoomId='string',reason='string',expectedVersion='integer')))
add('/residences/{id}/check-out','post','Residence','ActionRequest')
for action in ('accept','reject','start','finish','confirm','rework','cancel','notes'):
    add('/repairs/{id}/'+action,'post','Repair','ActionRequest')
add('/repairs/{id}/events','get',None)
paths['/repairs/{id}/events']['get']['responses']['200']['content']['application/json']['schema']=obj(dict(data='RepairEvent[]',traceId='string'))
add('/visitors/{id}/leave','post','Visitor',schemas['LeaveRequest']);add('/visitors/{id}/reveal-contact','post',None)
paths['/visitors/{id}/reveal-contact']['post']['responses']['200']['content']['application/json']['schema']=obj(dict(data=schemas['ContactResult'],traceId='string'))
add('/hygiene-inspections/{id}/void','post','Hygiene','ActionRequest')
add('/hygiene-inspections/{id}/history','get')
paths['/hygiene-inspections/{id}/history']['get']['responses']['200']['content']['application/json']['schema']=obj(dict(data='CorrectionEvent[]',traceId='string'))
for name, entity in [('overview','Overview'),('repairs','RepairStatistics'),('hygiene','HygieneStatistics'),('visitors','VisitorStatistics')]:add('/statistics/'+name,'get',entity,page=True)
add('/files','post','FileAsset');paths['/files']['post']['requestBody']={'required':True,'content':{'multipart/form-data':{'schema':obj(dict(file={'type':'string','format':'binary'},usage=enum('REPORT','RESULT','INSPECTION','REMEDIATION')))}}}
add('/files/{id}','delete');add('/files/{id}/content','get',binary=True);add('/files/{id}/thumbnail','get',binary=True)
add('/ai/repair-suggestions','post','AiSuggestion','AiRequest');add('/health','get')
add('/dispatch/heartbeat','post','DispatchManager','DispatchHeartbeatRequest')
add('/dispatch/board','get','DispatchBoard')
add('/dispatch/me','patch','DispatchManager','DispatchSettingsRequest')
add('/dispatch/replan','post','DispatchBoard','DispatchReplanRequest')
add('/dispatch/tasks/{orderId}/claim','post','Repair','DispatchClaimRequest')
for action in ('depart','arrive','block','resume'):
    add('/dispatch/tasks/{orderId}/'+action,'post','DispatchTask','DispatchTaskActionRequest')
add('/dispatch/tasks/{orderId}/handoff','post','Repair','DispatchHandoffRequest')
add('/dispatch/tasks/{orderId}/takeover','post','Repair','DispatchTakeoverRequest')
add('/dispatch/travel','get','DispatchTravel')
add('/dispatch/travel/{fromId}/{toId}','patch','DispatchTravelLink','DispatchTravelRequest')
add('/repairs/{id}/visit','patch','Repair','RepairVisitRequest')
add('/ai/manager/brief','post','ManagerAdvice','ManagerAssistRequest')
add('/ai/manager/repairs/{id}','post','ManagerAdvice','ManagerAssistRequest')
add('/ai/manager/repairs/{id}/feedback','get','AssistFeedbackList')
add('/ai/manager/repairs/{id}/feedback','post','AssistFeedback','AssistFeedbackRequest')
add('/messages','get','ServiceMessagePage')
add('/messages/summary','get','ServiceMessageSummary')
add('/messages/{id}/read','post','ServiceMessage','ServiceMessageReadRequest')
add('/hygiene-remediations','get','RemediationPage')
add('/hygiene-remediations','post','Remediation','RemediationRequest')
add('/hygiene-remediations/{id}','get','Remediation')
add('/hygiene-remediations/{id}','patch','Remediation','RemediationAssignmentRequest')
add('/hygiene-remediations/{id}/cancel','post','Remediation','RemediationCancelRequest')
add('/hygiene-remediations/{id}/feedback','post','Remediation','RemediationFeedbackRequest')
add('/hygiene-remediations/{id}/review','post','Remediation','RemediationReviewRequest')
for query_path, fields in {
    '/messages': {'page':'integer','pageSize':'integer','unreadOnly':'boolean'},
    '/hygiene-remediations': {'page':'integer','pageSize':'integer','status':'string','roomId':'string','buildingId':'string','inspectionId':'string','overdueOnly':'boolean'},
}.items():
    paths[query_path]['get']['parameters'].extend({'name':name,'in':'query','schema':{'type':value}} for name,value in fields.items())

add('/mobility/session','get','MobilityView')
add('/mobility/sessions','post','MobilityView','MobilityStartRequest')
for action in ('pause','resume','end'):
    add('/mobility/sessions/{id}/'+action,'post','MobilityView','MobilityActionRequest')
add('/mobility/sessions/{id}/samples','post','MobilityBatchAck','MobilityBatchRequest')
add('/mobility/sessions/{id}/anchor-confirmations','post','MobilityContext','MobilityAnchorRequest')
add('/mobility/anchors','get','CampusAnchors')
add('/mobility/anchors','post','CampusAnchor','CampusAnchorRequest')
add('/mobility/anchors/{id}','patch','CampusAnchor','CampusAnchorRequest')
add('/mobility/travel-models','get','MobilityTravelModels')
add('/mobility/efficiency','get','MobilityEfficiency')
add('/mobility/travel-models/{fromId}/{toId}/rollback','post','MobilityTravelModel','MobilityModelRollbackRequest')
doc={'openapi':'3.0.3','info':{'title':'DormLink Student Housing API','version':'1.3.0'},'servers':[{'url':'http://127.0.0.1:8080/api/v1'}],'paths':paths,'components':{'schemas':schemas,'securitySchemes':{'bearerAuth':{'type':'http','scheme':'bearer'},'cookieAuth':{'type':'apiKey','in':'cookie','name':'HD_SESSION'}}},'security':[{'bearerAuth':[]},{'cookieAuth':[]}]}
Path(__file__).with_name('openapi.json').write_text(json.dumps(doc,ensure_ascii=False,indent=2),encoding='utf-8')
