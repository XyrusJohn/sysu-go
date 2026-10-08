const test=require('node:test'),assert=require('node:assert/strict');
if(process.env.FIRESTORE_EMULATOR_HOST!=='127.0.0.1:8085'||process.env.FIREBASE_AUTH_EMULATOR_HOST!=='127.0.0.1:9099')throw Error('Both loopback emulators required');
for(const name of ['FIREBASE_SERVICE_ACCOUNT_JSON','FIREBASE_SERVICE_ACCOUNT','GOOGLE_APPLICATION_CREDENTIALS'])if(process.env[name])throw Error('Live credentials forbidden');
process.env.SYSU_ENV='emulator';process.env.FIREBASE_PROJECT_ID='demo-sysu-security';
const {getDb,getFirebaseAuth}=require('../../config/firebaseAdmin');const {getApp,deleteApp}=require('firebase-admin/app');
test('guarded SDK actually writes only demo Firestore and Auth emulators',async()=>{
 const db=getDb(),auth=getFirebaseAuth(),uid='isolation-'+process.pid,ref=db.collection('isolation_test').doc(uid);
 assert.equal(getApp().options.projectId,'demo-sysu-security');assert.equal(getDb(),db);assert.equal(getFirebaseAuth(),auth);
 let created=false;
 try{await ref.set({synthetic:true});assert.equal((await ref.get()).data().synthetic,true);await auth.createUser({uid});created=true;assert.equal((await auth.getUser(uid)).uid,uid);}
 finally{await ref.delete();if(created)await auth.deleteUser(uid);await db.terminate();await deleteApp(getApp());}
});
