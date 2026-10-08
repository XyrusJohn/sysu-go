const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
function load(file, mocks) {
 const module = {exports:{}};
 vm.runInNewContext(fs.readFileSync(path.join(root,file),'utf8'), {module,exports:module.exports,require(name){if(name in mocks)return mocks[name];throw Error('Unexpected import '+name);},console:{log(){},warn(){},error(){}},process:{env:{}},Date,Set}, {filename:file});
 return module.exports;
}
function response(){return {code:200,body:null,status(code){this.code=code;return this;},json(body){this.body=body;return this;}};}
async function authCase(options={}) {
 let next=0, checks=[], reads=0;
 const verifyToken = load('middleware/auth.js', {'../config/firebaseAdmin.js':{
 getFirebaseAuth(){return {async verifyIdToken(token, revoked){checks.push([token,revoked]);if(options.revoked && revoked)throw Error('revoked');return {uid:'driver-a'};}};},
 getDb(){return {collection(name){assert.equal(name,'users');return {doc(uid){assert.equal(uid,'driver-a');return {async get(){reads++;if(options.dbFailure)throw Error('private database details');return {exists:!options.missing,data:()=>options.profile || {role:'driver',status:'active'}};}};}};}};}
 }}).verifyToken;
 const req={headers:options.noHeader?{}:{authorization:'Bearer synthetic-token'}};
 const res=response(); await verifyToken(req,res,()=>next++);return {next,checks,reads,req,res};
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
