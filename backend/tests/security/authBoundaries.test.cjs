const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const Module = require('node:module');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
function load(file, mocks) {
 const filename=path.join(root,file);
 const module=new Module(filename);
 module.filename=filename;
 module.require=name=>{if(name in mocks)return mocks[name];throw Error('Unexpected import '+name);};
 module._compile(fs.readFileSync(filename,'utf8'),filename);
 return module.exports;
}
function response(){return {code:200,body:null,status(code){this.code=code;return this;},json(body){this.body=body;return this;}};}
let state;
const firebase = {
 getFirebaseAuth(){return {async verifyIdToken(token, revoked){state.checks.push([token,revoked]);if(state.options.revoked && revoked)throw Error('revoked');return {uid:'driver-a'};}};},
 getDb(){return {collection(name){assert.equal(name,'users');return {doc(uid){assert.equal(uid,'driver-a');return {async get(){state.reads++;if(state.options.dbFailure)throw Error('private database details');return {exists:!state.options.missing,data:()=>state.options.profile || {role:'driver',status:'active'}};}};}};}};}
};
const guard = load('middleware/auth.js', {'../config/firebaseAdmin.js':firebase}).verifyToken;
async function authCase(options={}) {
 state={options,next:0,checks:[],reads:0};
 const req={headers:options.noHeader?{}:{authorization:'Bearer synthetic-token'}};
 const res=response();await guard(req,res,()=>state.next++);return {...state,req,res};
}
test('active approved identity uses revoked-token verification',async()=>{const r=await authCase();assert.equal(r.next,1);assert.deepEqual(r.checks,[['synthetic-token',true]]);assert.equal(r.reads,1);assert.equal(r.req.user.uid,'driver-a');});
test('missing bearer header denied',async()=>{const r=await authCase({noHeader:true});assert.equal(r.res.code,401);assert.equal(r.next,0);});
test('Auth-only revoked token denied',async()=>{const r=await authCase({revoked:true});assert.equal(r.res.code,401);assert.equal(r.next,0);});
for(const status of ['disabled','inactive'])test(status+' approved profile denied',async()=>{const r=await authCase({profile:{role:'driver',status}});assert.equal(r.res.code,403);assert.equal(r.next,0);});
test('missing canonical profile denied',async()=>{const r=await authCase({missing:true});assert.equal(r.res.code,403);assert.equal(r.next,0);});
test('pending identity denied protected backend access',async()=>{const r=await authCase({profile:{role:'pending',status:'inactive'}});assert.equal(r.res.code,403);assert.equal(r.next,0);});
test('profile database failure fails closed without private error',async()=>{const r=await authCase({dbFailure:true});assert.equal(r.res.code,401);assert.equal(r.next,0);assert.ok(!JSON.stringify(r.res.body).includes('private'));});
for(const role of ['driver','super_admin'])for(const endpoint of ['/update-role','/delete'])test(role+' cannot mutate via retired '+endpoint,async()=>{
 const routes=new Map();let mutations=0;
 const ref={async update(){mutations++;},async delete(){mutations++;}};
 load('routes/userRoutes.js', {express:{Router:()=>({post(url,...handlers){routes.set(url,handlers);}})},'../middleware/auth.js':{verifyToken(req,res,next){next();}},'../config/firebaseAdmin.js':{getDb:()=>({collection:()=>({doc:()=>ref})}),getFirebaseAuth:()=>({async updateUser(){mutations++;}})}});
 const handlers=routes.get(endpoint);assert.ok(handlers);const res=response();const req={user:{uid:'caller',role},body:{uid:'victim',role:'super_admin'}};
 await handlers.at(-1)(req,res);assert.equal(res.code,410);assert.equal(mutations,0);
});

test('unknown profile role denied',async()=>{const r=await authCase({profile:{role:'attacker',status:'active'}});assert.equal(r.res.code,403);assert.equal(r.next,0);});
test('profile without explicit active status denied',async()=>{const r=await authCase({profile:{role:'driver'}});assert.equal(r.res.code,403);assert.equal(r.next,0);});
for(const endpoint of ['/update-role','/delete'])for(const authenticated of [false,true])test('HTTP '+endpoint+' '+(authenticated?'retired':'anonymous'),async()=>{
 const express=require('express');const http=require('node:http');let mutations=0;
 const firebase={getFirebaseAuth:()=>({async verifyIdToken(token,revoked){assert.equal(token,'synthetic-token');assert.equal(revoked,true);return {uid:'driver-a'};}}),getDb:()=>({collection:()=>({doc:()=>({async get(){return {exists:true,data:()=>({role:'driver',status:'active'})};},async update(){mutations++;},async delete(){mutations++;}})})})};
 state={options:{},next:0,checks:[],reads:0};
 const middleware={verifyToken:guard};
 const router=load('routes/userRoutes.js',{express,'../middleware/auth.js':middleware});
 const app=express();app.use(express.json());app.use('/api/users',router);const server=http.createServer(app);
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 try {
 const result=await fetch('http://127.0.0.1:'+server.address().port+'/api/users'+endpoint,{method:'POST',headers:{'Content-Type':'application/json',...(authenticated?{Authorization:'Bearer synthetic-token'}:{})},body:JSON.stringify({uid:'victim',role:'super_admin'})});
 assert.equal(result.status,authenticated?410:401);assert.equal((await result.json()).success,false);assert.equal(mutations,0);
 } finally {server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
});
