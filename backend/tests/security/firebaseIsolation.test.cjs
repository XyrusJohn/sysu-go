const test=require('node:test'),assert=require('node:assert/strict'),Module=require('node:module'),fs=require('node:fs'),path=require('node:path');
const emulator={SYSU_ENV:'emulator',FIREBASE_PROJECT_ID:'demo-sysu-security',FIRESTORE_EMULATOR_HOST:'127.0.0.1:8085',FIREBASE_AUTH_EMULATOR_HOST:'127.0.0.1:9099'};
function boot(env,existing){const events=[];const filename=path.resolve(__dirname,'../../config/firebaseAdmin.js'),mod=new Module(filename);mod.filename=filename;const fakeProcess={env,exit(){throw Error('configuration rejected');}};const app={options:{projectId:env.FIREBASE_PROJECT_ID}};mod.require=name=>{
 if(name==='firebase-admin/app')return {getApp(){if(existing)return existing;throw Error('not initialized');},initializeApp(options){events.push(options);app.options=options;existing=app;return app;},cert(account){return {projectId:account.project_id};}};
 if(name==='firebase-admin/firestore')return {getFirestore:()=>({})};if(name==='firebase-admin/auth')return {getAuth:()=>({})};
 if(name==='./runtimeConfig')return require('../../config/runtimeConfig');throw Error('Unexpected dependency');};
 mod._compile('const process=global.__isolationProcess; const console={log(){},error(){}};\n'+fs.readFileSync(filename,'utf8'),filename);global.__isolationProcess=fakeProcess;
 // Compiled functions read the process binding at import time, so reload after setting it.
 mod._compile('const process=global.__isolationProcess; const console={log(){},error(){}};\n'+fs.readFileSync(filename,'utf8'),filename);
 try{mod.exports.getDb();return events;}finally{delete global.__isolationProcess;}}
test('emulator initializes demo project without live credentials',()=>{const events=boot({...emulator});assert.equal(events[0].projectId,'demo-sysu-security');assert.equal(events[0].credential,undefined);});
for(const [name,override] of Object.entries({missingMode:{SYSU_ENV:undefined},liveProject:{FIREBASE_PROJECT_ID:'sysu-web-5a25d'},missingAuthEmulator:{FIREBASE_AUTH_EMULATOR_HOST:undefined},remoteHost:{FIRESTORE_EMULATOR_HOST:'evil.invalid:8085'},liveCredentials:{FIREBASE_SERVICE_ACCOUNT_JSON:'{}'},applicationCredentials:{GOOGLE_APPLICATION_CREDENTIALS:'/private/key.json'}}))test('emulator rejects '+name,()=>assert.throws(()=>boot({...emulator,...override})));
const production={SYSU_ENV:'production',FIREBASE_PROJECT_ID:'sysu-web-5a25d',FIREBASE_SERVICE_ACCOUNT_JSON:JSON.stringify({project_id:'sysu-web-5a25d',private_key:'synthetic-only'})};
test('production accepts explicit matching project credentials',()=>assert.equal(boot(production)[0].projectId,'sysu-web-5a25d'));
for(const [name,override] of Object.entries({mixedEmulator:{FIRESTORE_EMULATOR_HOST:'127.0.0.1:8085'},wrongCredential:{FIREBASE_SERVICE_ACCOUNT_JSON:JSON.stringify({project_id:'foreign',private_key:'synthetic'})},demoProject:{FIREBASE_PROJECT_ID:'demo-sysu-security'},malformedSecret:{FIREBASE_SERVICE_ACCOUNT_JSON:'SECRET-malformed'}}))test('production rejects '+name,()=>{assert.throws(()=>boot({...production,...override}),error=>!error.message.includes('SECRET'));});
test('staging cannot target known production project',()=>assert.throws(()=>boot({...production,SYSU_ENV:'staging'})));
test('preinitialized foreign app cannot bypass configuration',()=>assert.throws(()=>boot({...emulator},{options:{projectId:'sysu-web-5a25d'}})));
