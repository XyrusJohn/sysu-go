import {before,after,beforeEach,test} from 'node:test';
import assert from 'node:assert/strict';import {readFile} from 'node:fs/promises';
import {initializeTestEnvironment,assertFails,assertSucceeds} from '@firebase/rules-unit-testing';
import {doc,setDoc,updateDoc,getDoc,getDocs,collection,query,where,serverTimestamp,deleteDoc} from 'firebase/firestore';
let env;const db=uid=>env.authenticatedContext(uid).firestore();
before(async()=>{assert.equal(process.env.GCLOUD_PROJECT,'demo-sysu-security');assert.equal(process.env.FIRESTORE_EMULATOR_HOST,'127.0.0.1:8085');
const target=process.env.RULES_TARGET||'mobile';assert.ok(['mobile','web'].includes(target));
const source=target==='mobile'?new URL('../../firestore.rules',import.meta.url):new URL('../../../../sysu-app/frontend/firestore.rules',import.meta.url);
env=await initializeTestEnvironment({projectId:'demo-sysu-security',firestore:{host:'127.0.0.1',port:8085,rules:await readFile(source,'utf8')}});});
after(async()=>{await env?.cleanup()});

test('registration company persists for pending accounts without granting approval',async()=>{
  const ref=doc(db('new-driver'),'users','new-driver');
  await assertSucceeds(setDoc(ref,{uid:'new-driver',role:'pending',status:'inactive',truckingCompany:'Synthetic Transport'}));
  await assertSucceeds(updateDoc(ref,{truckingCompany:'Updated Transport'}));
  assert.equal((await getDoc(ref)).data().truckingCompany,'Updated Transport');
  await assertFails(updateDoc(ref,{role:'driver',status:'active'}));
  await assertFails(updateDoc(ref,{truckingCompany:'x'.repeat(101)}));
  await assertFails(updateDoc(doc(db('other-driver'),'users','new-driver'),{truckingCompany:'Foreign'}));
});

test('AUTOMATION owned native loading metadata persists with the state transition',async()=>{
  await assertSucceeds(updateDoc(doc(db('a'),'sap_assignments','shipment'),{tripStatus:'loading',loadingStartedAt:new Date()}));
});
test('AUTOMATION owned native fully-loaded metadata persists with the state transition',async()=>{
  await env.withSecurityRulesDisabled(ctx=>updateDoc(doc(ctx.firestore(),'sap_assignments','shipment'),{tripStatus:'loading'}));
  await assertSucceeds(updateDoc(doc(db('a'),'sap_assignments','shipment'),{tripStatus:'fully_loaded',fullyLoadedAt:new Date()}));
});
test('AUTOMATION fresh captured GPS timestamp is accepted without inventing commit time',async()=>{
  await assertSucceeds(setDoc(doc(db('a'),'driver_locations','a'),{driverId:'a',name:'Synthetic',lat:14.5,lng:121,
    status:'online',sapDocId:'shipment',updatedAt:new Date(Date.now()-1000)}));
  await assertSucceeds(updateDoc(doc(db('a'),'sap_assignments','shipment'),{lastLocation:{lat:14.5,lng:121,at:new Date(Date.now()-1000)}}));
});
test('AUTOMATION return-to-base GPS remains owned and active until closure',async()=>{
  await env.withSecurityRulesDisabled(ctx=>updateDoc(doc(ctx.firestore(),'sap_assignments','shipment'),{tripStatus:'returning_to_base'}));
  await assertSucceeds(setDoc(doc(db('a'),'driver_locations','a'),{driverId:'a',name:'Synthetic',lat:14.5,lng:121,
    status:'online',sapDocId:'shipment',updatedAt:serverTimestamp()}));
  await assertSucceeds(updateDoc(doc(db('a'),'sap_assignments','shipment'),{lastLocation:{lat:14.5,lng:121,at:serverTimestamp()}}));
});
test('AUTOMATION foreign progress, unexpected cargo and stale/future GPS stay denied',async()=>{
  for(const change of [{tripStatus:'loading',driverId:'b'},{tripStatus:'loading',loadingStartedAt:new Date(0)},
    {tripStatus:'loading',loadingStartedAt:new Date(),lineItems:[]}]){
    await assertFails(updateDoc(doc(db('a'),'sap_assignments','shipment'),change));
  }
  await assertFails(updateDoc(doc(db('b'),'sap_assignments','shipment'),{tripStatus:'loading',loadingStartedAt:new Date()}));
  for(const at of [new Date(0),new Date(Date.now()+60000)]){
    await assertFails(setDoc(doc(db('a'),'driver_locations','a'),{driverId:'a',name:'Synthetic',lat:14.5,lng:121,
      status:'online',sapDocId:'shipment',updatedAt:at}));
  }
});
beforeEach(async()=>{await env.clearFirestore();await env.withSecurityRulesDisabled(async ctx=>{
const admin=ctx.firestore();for(const [id,role,status] of [['a','driver','active'],['b','driver','active'],['staff','fleet_manager','active'],['pending','pending','inactive'],['disabled','driver','disabled']])await setDoc(doc(admin,'users',id),{name:id,role,status});
await setDoc(doc(admin,'users','missing-status'),{role:'driver'});await setDoc(doc(admin,'users','unknown-role'),{role:'unknown',status:'active'});
await setDoc(doc(admin,'queue','qa'),{driverId:'a',status:'waiting',registrationComplete:false,isFull:false});await setDoc(doc(admin,'drivers','a'),{driverId:'a',driverName:'Synthetic',isFull:false});await setDoc(doc(admin,'sap_assignments','shipment'),{driverId:'a',tripStatus:'assigned',lineItems:[{obdNumber:'001'},{obdNumber:'002'}]});
await setDoc(doc(admin,'truck_company','test'),{name:'Synthetic'});await setDoc(doc(admin,'security_guard','test'),{name:'Synthetic'});
});});
test('G07 own account cannot self-promote or activate',async()=>{await assertFails(updateDoc(doc(db('a'),'users','a'),{role:'super_admin'}));await assertFails(updateDoc(doc(db('pending'),'users','pending'),{role:'driver',status:'active'}));});
test('G07 new signup cannot self-approve as driver',async()=>await assertFails(setDoc(doc(db('new'),'users','new'),{name:'New',role:'driver',status:'active'})));
test('G07 anonymous cannot read user or assignment',async()=>{const anon=env.unauthenticatedContext().firestore();await assertFails(getDoc(doc(anon,'users','a')));await assertFails(getDoc(doc(anon,'sap_assignments','shipment')));});
test('G07 foreign driver cannot change user, queue, driver or assignment',async()=>{for(const [coll,id,change] of [['users','a',{name:'Foreign'}],['queue','qa',{isFull:true}],['drivers','a',{driverName:'Foreign'}],['sap_assignments','shipment',{tripStatus:'loading'}]])await assertFails(updateDoc(doc(db('b'),coll,id),change));});
test('G07 clients cannot fabricate queue or assignment',async()=>{await assertFails(setDoc(doc(db('a'),'queue','forged'),{driverId:'a',status:'waiting'}));await assertFails(setDoc(doc(db('staff'),'sap_assignments','forged'),{driverId:'a'}));});
test('G07 unrelated driver cannot enumerate operational data',async()=>{await assertFails(getDocs(collection(db('a'),'queue')));await assertFails(getDocs(collection(db('b'),'sap_assignments')));});
test('G07 missing, unknown or disabled profiles cannot read own operational data',async()=>{for(const uid of ['missing-status','unknown-role','disabled']){await env.withSecurityRulesDisabled(ctx=>setDoc(doc(ctx.firestore(),'queue','own-'+uid),{driverId:uid}));await assertFails(getDoc(doc(db(uid),'queue','own-'+uid)));}});
test('G16 pending signup accepts bounded split-name profile without driver document',async()=>{await assertSucceeds(setDoc(doc(db('new'),'users','new'),{uid:'new',email:'synthetic@example.invalid',fn:'First',mi:'M',ln:'Last',name:'First M. Last',phone:'09123456789',role:'pending',status:'inactive',position:'Driver'}));await assertFails(setDoc(doc(db('new'),'drivers','new'),{driverId:'new'}));});
test('G16 own basic profile completion preserves approval',async()=>{await assertSucceeds(updateDoc(doc(db('a'),'users','a'),{fn:'First',mi:'',ln:'Last',name:'First Last',phone:'09123456789'}));await assertFails(updateDoc(doc(db('a'),'users','a'),{uid:'b'}));});
test('G16 oversized or typed profile data rejected',async()=>{await assertFails(updateDoc(doc(db('a'),'users','a'),{fn:'x'.repeat(51)}));await assertFails(updateDoc(doc(db('a'),'users','a'),{mi:42}));});
test('G16 own UID query and permitted queue capacity update work',async()=>{await assertSucceeds(getDocs(query(collection(db('a'),'queue'),where('driverId','==','a'))));await assertSucceeds(updateDoc(doc(db('a'),'queue','qa'),{isFull:true}));await assertFails(updateDoc(doc(db('a'),'queue','qa'),{status:'completed'}));});
test('G16 own canonical profile and lawful assignment transition work',async()=>{await assertSucceeds(getDoc(doc(db('a'),'drivers','a')));await assertSucceeds(updateDoc(doc(db('a'),'drivers','a'),{driverName:'Updated'}));await assertSucceeds(updateDoc(doc(db('a'),'sap_assignments','shipment'),{tripStatus:'loading'}));await assertFails(updateDoc(doc(db('a'),'sap_assignments','shipment'),{driverId:'b'}));});
test('G16 owner reads both OBDs but cannot rewrite shipment cargo',async()=>{const snap=await assertSucceeds(getDoc(doc(db('a'),'sap_assignments','shipment')));assert.equal(snap.data().lineItems.length,2);await assertFails(updateDoc(doc(db('a'),'sap_assignments','shipment'),{lineItems:[]}));});
test('G16 dropdowns require authentication and remain read-only',async()=>{for(const name of ['truck_company','security_guard']){await assertSucceeds(getDocs(collection(db('a'),name)));await assertFails(getDocs(collection(env.unauthenticatedContext().firestore(),name)));await assertFails(setDoc(doc(db('a'),name,'forged'),{name:'Forged'}));}});

test('G07 staff identity cannot perform driver mutations on legacy owned records',async()=>{await env.withSecurityRulesDisabled(async ctx=>{const admin=ctx.firestore();await setDoc(doc(admin,'queue','legacy-staff'),{driverId:'staff',isFull:false});await setDoc(doc(admin,'drivers','staff'),{driverId:'staff',driverName:'Legacy'});await setDoc(doc(admin,'sap_assignments','legacy-staff'),{driverId:'staff',tripStatus:'assigned'});});for(const [coll,id,change] of [['queue','legacy-staff',{isFull:true}],['drivers','staff',{driverName:'Changed'}],['sap_assignments','legacy-staff',{tripStatus:'loading'}]])await assertFails(updateDoc(doc(db('staff'),coll,id),change));});

const requirements={aircon:true,reflectorizedVest:false,safetyShoes:true,headcap:false,pants:true,closedShoes:true};
const location=(extra={})=>({driverId:'a',name:'Synthetic',lat:14.5,lng:121,status:'online',sapDocId:null,updatedAt:serverTimestamp(),...extra});
test('G16 shipped legacy own profile merge and requirements succeed without authority changes',async()=>{await assertSucceeds(updateDoc(doc(db('a'),'drivers','a'),{name:'First Last',fn:'First',mi:'',ln:'Last',truckType:'6W',plateNumber:'ABC1234',truckingCompany:'Synthetic',helper:'Helper',requirements,updatedAt:serverTimestamp()}));await assertSucceeds(updateDoc(doc(db('a'),'drivers','a'),{requirements:{...requirements,aircon:false}}));});
test('G16 requirements reject unknown keys, non-booleans and foreign or inactive writers',async()=>{for(const change of [{requirements:{...requirements,approved:true}},{requirements:{...requirements,aircon:'yes'}},{assignedShipments:['forged']},{status:'available'},{driverId:'b'}])await assertFails(updateDoc(doc(db('a'),'drivers','a'),change));for(const uid of ['b','staff','disabled','pending'])await assertFails(updateDoc(doc(db(uid),'drivers','a'),{requirements}));});
test('G16 legacy profile bounds reject unsafe fields',async()=>{for(const change of [{name:'x'.repeat(101)},{plateNumber:'x'.repeat(21)},{truckType:42},{helper:'x'.repeat(301)},{requirements:null}])await assertFails(updateDoc(doc(db('a'),'drivers','a'),change));});
test('G16 own fresh location supports movement, stopped and cleanup',async()=>{await assertSucceeds(setDoc(doc(db('a'),'driver_locations','a'),location()));await assertSucceeds(updateDoc(doc(db('a'),'driver_locations','a'),{status:'stopped',updatedAt:serverTimestamp()}));await assertSucceeds(deleteDoc(doc(db('a'),'driver_locations','a')));});
test('G16 private location allows staff and own driver reads only',async()=>{await env.withSecurityRulesDisabled(ctx=>setDoc(doc(ctx.firestore(),'driver_locations','a'),{...location(),updatedAt:new Date()}));await assertSucceeds(getDoc(doc(db('a'),'driver_locations','a')));await assertSucceeds(getDocs(collection(db('staff'),'driver_locations')));await assertFails(getDoc(doc(db('b'),'driver_locations','a')));await assertFails(getDocs(collection(db('a'),'driver_locations')));await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(),'driver_locations','a')));});
test('G16 location rejects forged UID, coordinates, timestamps and unknown metadata',async()=>{for(const change of [{driverId:'b'},{lat:91},{lng:-181},{lat:'14.5'},{name:'x'.repeat(101)},{status:'available'},{updatedAt:new Date(0)},{role:'admin'}])await assertFails(setDoc(doc(db('a'),'driver_locations','a'),location(change)));for(const uid of ['b','staff','disabled','pending'])await assertFails(setDoc(doc(db(uid),'driver_locations','a'),location()));});
test('G16 location shipment link requires canonical owned active assignment',async()=>{await assertSucceeds(setDoc(doc(db('a'),'driver_locations','a'),location({sapDocId:'shipment'})));for(const id of ['missing','foreign','completed']){if(id!=='missing')await env.withSecurityRulesDisabled(ctx=>setDoc(doc(ctx.firestore(),'sap_assignments',id),{driverId:id==='foreign'?'b':'a',tripStatus:id==='completed'?'completed':'assigned'}));await assertFails(setDoc(doc(db('a'),'driver_locations','a'),location({sapDocId:id})));}});
test('G16 owned lastLocation update preserves cargo and authority',async()=>{await assertSucceeds(updateDoc(doc(db('a'),'sap_assignments','shipment'),{'lastLocation.lat':14.5,'lastLocation.lng':121,'lastLocation.at':serverTimestamp()}));await assertFails(updateDoc(doc(db('b'),'sap_assignments','shipment'),{lastLocation:{lat:14,lng:120,at:serverTimestamp()}}));await assertFails(updateDoc(doc(db('a'),'sap_assignments','shipment'),{lastLocation:{lat:14,lng:120,at:serverTimestamp()},lineItems:[]}));});
test('G16 lastLocation invalid or completed-shipment writes denied',async()=>{for(const value of [{lat:100,lng:121,at:serverTimestamp()},{lat:14,lng:121,at:new Date(0)},{lat:14,lng:121,at:serverTimestamp(),speed:20}])await assertFails(updateDoc(doc(db('a'),'sap_assignments','shipment'),{lastLocation:value}));await env.withSecurityRulesDisabled(ctx=>updateDoc(doc(ctx.firestore(),'sap_assignments','shipment'),{tripStatus:'completed'}));await assertFails(updateDoc(doc(db('a'),'sap_assignments','shipment'),{lastLocation:{lat:14,lng:121,at:serverTimestamp()}}));});
