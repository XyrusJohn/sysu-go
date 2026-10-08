const {initializeApp,getApp,cert}=require('firebase-admin/app');
const {getFirestore}=require('firebase-admin/firestore');
const {getAuth}=require('firebase-admin/auth');
const {readFirebaseConfig}=require('./runtimeConfig');
let app,db,auth;
function getAdminApp(){
 if(app)return app;
 const config=readFirebaseConfig(process.env);
 let existing;try{existing=getApp();}catch(_){}
 if(existing){
  // Never inherit an app initialized outside this guarded module.
  throw Error('Firebase app was initialized outside the guarded configuration');
 }
 const options={projectId:config.projectId};
 if(config.account)options.credential=cert(config.account);
 app=initializeApp(options);return app;
}
function getDb(){if(!db)db=getFirestore(getAdminApp());return db;}
function getFirebaseAuth(){if(!auth)auth=getAuth(getAdminApp());return auth;}
module.exports={getDb,getFirebaseAuth};
