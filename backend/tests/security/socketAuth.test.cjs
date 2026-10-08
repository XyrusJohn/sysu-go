const test=require('node:test'),assert=require('node:assert/strict');
const {createSocketAuth}=require('../../middleware/socketAuth.js');

function harness({token='valid',role='driver',status='active',verifyError,readError}={}){
 const calls=[];let disconnects=0;let current={role,status};
 const auth={verifyIdToken:async(value,revoked)=>{calls.push(['verify',value,revoked]);if(verifyError)throw verifyError;return {uid:'uid-a',exp:Math.floor(Date.now()/1000)+3600};}};
 const db={collection:()=>({doc:uid=>({get:async()=>{calls.push(['profile',uid]);if(readError)throw readError;return {exists:!!current,data:()=>current};}})})};
 const socket={id:'synthetic',handshake:{auth:token?{token}:{},headers:{}},data:{},use(fn){this.packetGuard=fn;},disconnect(){disconnects++;}};
 const middleware=createSocketAuth({getFirebaseAuth:()=>auth,getDb:()=>db});
 return {socket,middleware,calls,setProfile:p=>current=p,get disconnects(){return disconnects;}};
}
function connect(h){return new Promise(resolve=>h.middleware(h.socket,error=>resolve(error)));}
function packet(h,event,payload={}){return new Promise(resolve=>h.socket.packetGuard([event,payload],error=>resolve(error)));}

test('G13 rejects missing token before a socket is accepted',async()=>{const h=harness({token:''});const error=await connect(h);assert.ok(error);assert.equal(h.socket.data.uid,undefined);});
test('G13 verifies token revocation and records only canonical actor identity',async()=>{const h=harness();assert.equal(await connect(h),undefined);assert.deepEqual(h.calls.slice(0,2),[['verify','valid',true],['profile','uid-a']]);assert.equal(h.socket.data.uid,'uid-a');assert.equal(h.socket.data.role,'driver');});
for(const [label,options] of [['revoked',{verifyError:Error('private revoked token')}],['disabled',{status:'disabled'}],['pending',{role:'pending',status:'inactive'}],['unknown role',{role:'attacker'}],['profile outage',{readError:Error('private db outage')} ]])test('G13 denies '+label+' without leaking details',async()=>{const h=harness(options);const error=await connect(h);assert.ok(error);assert.doesNotMatch(error.message,/private/);});
test('G13 rejects role changes and Auth revocation on the next event',async()=>{const h=harness();assert.equal(await connect(h),undefined);h.setProfile({role:'admin',status:'active'});const error=await packet(h,'heartbeat');assert.ok(error);assert.equal(h.disconnects,1);});
test('G13 driver cannot emit unapproved event names',async()=>{const h=harness();assert.equal(await connect(h),undefined);const error=await packet(h,'make_admin',{uid:'victim'});assert.ok(error);assert.equal(h.disconnects,1);});
test('G13 driver events must remain self scoped and bounded',async()=>{const h=harness();assert.equal(await connect(h),undefined);const foreign=await packet(h,'driver_stopped',{driverId:'victim'});assert.ok(foreign);const other=harness();assert.equal(await connect(other),undefined);const oversized=await packet(other,'driver_stopped',{driverId:'uid-a',data:'x'.repeat(9000)});assert.ok(oversized);});
