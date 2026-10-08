// Legacy import uses the same guarded singleton; never writes credentials to disk.
module.exports=require('./firebaseAdmin').getDb();
