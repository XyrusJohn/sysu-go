package com.sysu.go.config
import java.net.URI
object EnvironmentPolicy {
 fun validate(debug: Boolean, project: String?, api: String, socket: String, frontend: String) {
  val expected=if(debug) listOf("http://10.0.2.2:3000","http://10.0.2.2:3000","http://10.0.2.2:5173") else listOf("https://sysu-go-production.up.railway.app","https://sysu-go-production.up.railway.app","https://sysu-web-5a25d.web.app")
  require(project==if(debug) "demo-sysu-security" else "sysu-web-5a25d") {"Invalid Firebase project"}
  require(listOf(api,socket,frontend)==expected) {"Invalid service configuration"}
  for(value in expected){val uri=URI(value);require(uri.userInfo==null && uri.query==null && uri.fragment==null)}
 }
}
