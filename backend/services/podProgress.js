const MAX_IMAGE_CHARS=800000;
function fail(status,message){const error=new Error(message);error.status=status;throw error;}
function validatePodRequest(input,now=Date.now()){
 if(!input||typeof input!=='object'||Array.isArray(input))fail(400,'Invalid POD request');
 const keys=input.action==='complete'?['action','base64Image','deliveryStatus','capturedAtMillis']:input.action==='unload'?['action','unloadStatus']:['action'];
 if(Object.keys(input).some(key=>!keys.includes(key)))fail(400,'Invalid POD request');
 if(!['complete','unload','retake'].includes(input.action))fail(400,'Invalid POD action');
 if(input.action==='unload'&&!['','UNLOADING'].includes(input.unloadStatus))fail(400,'Invalid unload status');
 if(input.action==='complete'){
  if(!['All Delivered','Rejected','Delivered w/ Rejection'].includes(input.deliveryStatus))fail(400,'Invalid delivery status');
  const image=input.base64Image;
  if(typeof image!=='string'||image.length>MAX_IMAGE_CHARS||!/^data:image\/jpeg;base64,[A-Za-z0-9+/]+={0,2}$/.test(image))fail(400,'Invalid POD image');
  const raw=image.slice(23),bytes=Buffer.from(raw,'base64');
  if(bytes.length<4||bytes.toString('base64')!==raw||bytes[0]!==255||bytes[1]!==216||bytes[2]!==255)fail(400,'Invalid POD image');
  if(!Number.isSafeInteger(input.capturedAtMillis)||input.capturedAtMillis<=0||input.capturedAtMillis>now+60000||input.capturedAtMillis<now-86400000)fail(400,'Invalid capture time');
 }
 return input;
}
function applyPodProgress(assignment,uid,obdNumber,input,now=Date.now()){
 validatePodRequest(input,now);
 if(!assignment||assignment.driverId!==uid)fail(403,'Shipment not authorized');
 if(assignment.tripStatus!=='in_transit')fail(409,'Shipment is not in transit');
 const rows=assignment.lineItems;
 if(!Array.isArray(rows)||rows.some(row=>!row||typeof row!=='object'||Array.isArray(row)))fail(409,'Invalid shipment items');
 if(!rows.some(row=>row.obdNumber===obdNumber))fail(404,'OBD not found');
 if(input.action==='unload'&&rows.some(row=>row.obdNumber===obdNumber&&row.isPodCompleted===true))fail(409,'Retake POD before unloading');
 return rows.map(row=>{
  if(row.obdNumber!==obdNumber)return row;
  if(input.action==='unload')return {...row,unloadStatus:input.unloadStatus};
  return {...row,deliveryStatus:input.action==='complete'?input.deliveryStatus:'',podImageUrl:input.action==='complete'?'saved_in_subcollection':'',podCapturedAt:input.action==='complete'?input.capturedAtMillis:null,isPodCompleted:input.action==='complete',unloadStatus:input.action==='complete'?null:'UNLOADING'};
 });
}
module.exports={applyPodProgress,validatePodRequest,MAX_IMAGE_CHARS};
