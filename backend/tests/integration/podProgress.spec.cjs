const {test}=require('node:test');const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module');
const express=require('express');const {initializeApp,deleteApp}=require('firebase-admin/app');const {getFirestore}=require('firebase-admin/firestore');
test('shipped POD HTTP route with real Firestore atomic image/OBD transactions', {skip:!process.env.FIRESTORE_EMULATOR_HOST},async()=>{
 const admin=initializeApp({projectId:'demo-sysu-security'},'pod-'+Date.now());const db=getFirestore(admin);
 const filename=path.resolve(__dirname,'../../routes/podRoutes.js'),native=Module.createRequire(filename),m=new Module(filename);
 const authFile=path.resolve(__dirname,'../../middleware/auth.js'),authModule=new Module(authFile);
 authModule.require=name=>name==='../config/firebaseAdmin.js'?{getDb:()=>db,getFirebaseAuth:()=>({verifyIdToken:async token=>{if(!['driver-a','driver-b','staff-a'].includes(token))throw Error('invalid');return {uid:token};}})}:Module.createRequire(authFile)(name);
 authModule._compile(fs.readFileSync(authFile,'utf8'),authFile);
 m.require=name=>name==='../config/firebaseAdmin'?{getDb:()=>db}:name==='../middleware/auth'?authModule.exports:native(name);
 m._compile(fs.readFileSync(filename,'utf8'),filename);
 const app=express(),events=[];app.use(express.json({limit:'2mb'}));app.set('io',{to:room=>({emit:(event,payload)=>events.push({room,event,payload})})});app.use('/api/pod',m.exports);
 const server=await new Promise(resolve=>{const s=app.listen(0,'127.0.0.1',()=>resolve(s));});
 const id='pod-test-'+Date.now(),ref=db.collection('sap_assignments').doc(id),image=ref.collection('pod_images').doc('OBD-A');
 const rows=[{obdNumber:'OBD-A',quantity:10},{obdNumber:'OBD-B',quantity:20},{obdNumber:'OBD-A',quantity:5}];
 const base={driverId:'driver-a',tripStatus:'in_transit',lineItems:rows};const body={action:'complete',base64Image:'data:image/jpeg;base64,/9j/AA==',deliveryStatus:'All Delivered',capturedAtMillis:Date.now()};
 async function post(token,payload,obd='OBD-A'){return fetch('http://127.0.0.1:'+server.address().port+'/api/pod/'+id+'/'+obd,{method:'POST',headers:{'content-type':'application/json',...(token?{authorization:'Bearer '+token}:{})},body:JSON.stringify(payload)});}
 async function postGroup(token,payload){return fetch('http://127.0.0.1:'+server.address().port+'/api/pod/'+id+'/group',{method:'POST',headers:{'content-type':'application/json',...(token?{authorization:'Bearer '+token}:{})},body:JSON.stringify(payload)});}
 try {
  for(const [uid,role] of [['driver-a','driver'],['driver-b','driver'],['staff-a','fleet_manager']])await db.collection('users').doc(uid).set({role,status:'active'});
  await ref.set(base);
  assert.equal((await post(null,body)).status,401);assert.equal((await post('staff-a',body)).status,403);assert.equal((await post('driver-b',body)).status,403);
  assert.equal((await image.get()).exists,false);assert.equal(events.length,0);
  assert.equal((await post('driver-a',{...body,quantity:999})).status,400);
  assert.equal((await post('driver-a',body,'MISSING')).status,404);assert.equal((await image.get()).exists,false);
  const replies=await Promise.all([post('driver-a',body),post('driver-a',body)]);assert.ok(replies.every(response=>response.status===200));
  let after=(await ref.get()).data();assert.equal(after.lineItems[0].isPodCompleted,true);assert.equal(after.lineItems[2].isPodCompleted,true);assert.deepEqual(after.lineItems[1],rows[1]);assert.equal(after.lineItems[0].quantity,10);assert.equal(after.lineItems[2].quantity,5);
  const stored=(await image.get()).data();assert.equal(stored.base64Image,body.base64Image);assert.equal(stored.driverId,'driver-a');assert.equal(stored.capturedAt.toMillis(),body.capturedAtMillis);assert.ok(stored.recordedAt.toMillis()>=body.capturedAtMillis);
  assert.ok(events.every(event=>event.room==='staff:tracking'));
  assert.equal((await post('driver-a',{action:'unload',unloadStatus:'UNLOADING'})).status,409);
  assert.equal((await post('driver-a',{action:'retake'})).status,200);assert.equal((await image.get()).exists,false);after=(await ref.get()).data();assert.equal(after.lineItems[0].isPodCompleted,false);assert.deepEqual(after.lineItems[1],rows[1]);
  await ref.update({tripStatus:'completed'});const count=events.length;assert.equal((await post('driver-a',body)).status,409);assert.equal(events.length,count);assert.equal((await image.get()).exists,false);
  await db.collection('users').doc('driver-a').update({status:'disabled'});assert.equal((await post('driver-a',body)).status,403);
  await db.collection('users').doc('driver-a').update({status:'active'});
  const groupRows=[
   {obdNumber:'OBD-X',shipTo:'0001',customerName:'Synthetic Market',deliveryAddress:'Synthetic address',latitude:14.6,longitude:120.9,quantity:2,unloadStatus:null},
   {obdNumber:'OBD-Y',shipTo:'0001',customerName:'Synthetic Market',deliveryAddress:'Synthetic address',latitude:14.6,longitude:120.9,quantity:3,unloadStatus:null},
   {obdNumber:'OBD-Z',shipTo:'0001',customerName:'Synthetic Market',deliveryAddress:'Other address',latitude:14.6,longitude:120.9,quantity:4,unloadStatus:null},
  ];
  await ref.set({...base,lineItems:groupRows});
  const ids=['OBD-X','OBD-Y'],captureAt=Date.now(),groupBody={action:'complete',obdNumbers:ids,captureId:'capture-1234567890',base64Image:body.base64Image,deliveryStatus:'All Delivered',capturedAtMillis:captureAt};
  assert.equal((await postGroup('driver-b',{action:'unload',obdNumbers:ids,unloadStatus:'UNLOADING'})).status,403);
  assert.equal((await postGroup('driver-a',{action:'unload',obdNumbers:ids,unloadStatus:'UNLOADING'})).status,200);
  assert.equal((await postGroup('driver-a',groupBody)).status,200);
  let groupAfter=(await ref.get()).data();
  assert.equal(groupAfter.lineItems[0].isPodCompleted,true);assert.equal(groupAfter.lineItems[1].isPodCompleted,true);
  assert.equal(groupAfter.lineItems[2].isPodCompleted,undefined);
  assert.equal(groupAfter.lineItems[0].podGroupId,groupAfter.lineItems[1].podGroupId);
  const groupId=groupAfter.lineItems[0].podGroupId,groupImage=ref.collection('pod_groups').doc(groupId);
  assert.equal((await groupImage.get()).data().base64Image,body.base64Image);
  assert.equal((await ref.collection('pod_images').doc('OBD-X').get()).data().base64Image,undefined);
  assert.equal((await ref.collection('pod_images').doc('OBD-X').get()).data().groupPodId,groupId);
  assert.equal((await postGroup('driver-a',groupBody)).status,200);
  assert.equal((await ref.collection('pod_groups').doc(groupId).get()).exists,true);
  assert.equal((await post('driver-a',{action:'retake'},'OBD-X')).status,200);
  assert.equal((await groupImage.get()).exists,true);
  assert.equal((await post('driver-a',{action:'retake'},'OBD-Y')).status,200);
  assert.equal((await groupImage.get()).exists,false);
 }finally{await ref.delete();await image.delete();await new Promise(resolve=>server.close(resolve));await deleteApp(admin);}
});
