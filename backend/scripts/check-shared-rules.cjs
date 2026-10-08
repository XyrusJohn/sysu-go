const fs=require('node:fs'),path=require('node:path');
const canonical=path.resolve(__dirname,'../../../sysu-app/frontend/firestore.rules'),mobile=path.resolve(__dirname,'../firestore.rules');
if(!fs.existsSync(canonical))throw Error('Adjacent sysu-app canonical rules checkout required for shared release verification');
if(fs.readFileSync(canonical,'utf8').replace(/\r\n/g,'\n')!==fs.readFileSync(mobile,'utf8').replace(/\r\n/g,'\n'))throw Error('Mobile rule copy differs from canonical sysu-app rules; review and synchronize before release');
console.log('Shared Firestore rule copies match');
