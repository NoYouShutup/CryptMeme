<%@page contentType="text/html" import="net.i2p.i2ptunnel.web.EditBean"
%><%@page trimDirectiveWhitespaces="true"
%><?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<jsp:useBean class="net.i2p.i2ptunnel.web.EditBean" id="editBean" scope="request" />
<jsp:useBean class="net.i2p.i2ptunnel.web.Messages" id="intl" scope="request" />
<% String tun = request.getParameter("tunnel");
   int curTunnel = -1;
   if (tun != null) {
     try {
       curTunnel = Integer.parseInt(tun);
     } catch (NumberFormatException nfe) {
       curTunnel = -1;
     }
   }
%>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
<head>
    <title><%=intl._("Hidden Services Manager")%> - <%=intl._("Edit Client Tunnel")%></title>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta http-equiv="Content-Type" content="application/xhtml+xml; charset=UTF-8" />
    <link href="/themes/console/images/favicon.ico" type="image/x-icon" rel="shortcut icon" />

    <% if (editBean.allowCSS()) {
  %><link rel="icon" href="<%=editBean.getTheme()%>images/favicon.ico" />
    <link href="<%=editBean.getTheme()%>default.css" rel="stylesheet" type="text/css" /> 
    <link href="<%=editBean.getTheme()%>i2ptunnel.css" rel="stylesheet" type="text/css" />
    <% }
  %>
<style type='text/css'>
input.default { width: 1px; height: 1px; visibility: hidden; }
</style>
</head>
<body id="tunnelEditPage">
    <div id="pageHeader">
    </div>
<%

  if (editBean.isInitialized()) {

%>
    <form method="post" action="list">

        <div id="tunnelEditPanel" class="panel">
            <div class="header">
                <%
                String tunnelTypeName;
                String tunnelType;
                if (curTunnel >= 0) {
                    tunnelTypeName = editBean.getTunnelType(curTunnel);
                    tunnelType = editBean.getInternalType(curTunnel);
                  %><h4><%=intl._("Edit proxy settings")%></h4><% 
                } else {
                    tunnelTypeName = editBean.getTypeName(request.getParameter("type"));
                    tunnelType = net.i2p.data.DataHelper.stripHTML(request.getParameter("type"));
                  %><h4><%=intl._("New proxy settings")%></h4><% 
                } %>
                <input type="hidden" name="tunnel" value="<%=curTunnel%>" />
                <input type="hidden" name="nonce" value="<%=editBean.getNextNonce()%>" />
                <input type="hidden" name="type" value="<%=tunnelType%>" />
                <%
                // these are four keys that are generated automatically on first save,
                // and we want to persist in i2ptunnel.config, but don't want to
                // show clogging up the custom options form.
                String key = editBean.getKey1(curTunnel);
                if (key != null && key.length() > 0) { %>
                    <input type="hidden" name="key1" value="<%=key%>" />
                <% }
                key = editBean.getKey2(curTunnel);
                if (key != null && key.length() > 0) { %>
                    <input type="hidden" name="key2" value="<%=key%>" />
                <% }
                key = editBean.getKey3(curTunnel);
                if (key != null && key.length() > 0) { %>
                    <input type="hidden" name="key3" value="<%=key%>" />
                <% }
                key = editBean.getKey4(curTunnel);
                if (key != null && key.length() > 0) { %>
                    <input type="hidden" name="key4" value="<%=key%>" />
                <% } %>
                <input type="submit" class="default" name="action" value="Save changes" />
            </div>
      
            <div class="separator">
                <hr />
            </div>

            <div id="nameField" class="rowItem">
                <label for="name" accesskey="N">
                    <%=intl._("Name")%>:(<span class="accessKey">N</span>)
                </label>
                <input type="text" size="30" maxlength="50" name="name" id="name" title="Tunnel Name" value="<%=editBean.getTunnelName(curTunnel)%>" class="freetext" />               
            </div>
            <div id="typeField" class="rowItem">
                <label><%=intl._("Type")%>:</label>
                <span class="text"><%=tunnelTypeName%></span>
            </div>
            <div id="descriptionField" class="rowItem">
                <label for="description" accesskey="e">
                    <%=intl._("Description")%>:(<span class="accessKey">E</span>)
                </label>
                <input type="text" size="60" maxlength="80" name="nofilter_description"  id="description" title="Tunnel Description" value="<%=editBean.getTunnelDescription(curTunnel)%>" class="freetext" />                
            </div>
                 
            <div class="subdivider">
                <hr />
            </div>
                 
            <div id="accessField" class="rowItem">
         <% if ("streamrclient".equals(tunnelType)) { %>
                <label><%=intl._("Target")%>:</label>
         <% } else { %>
                <label><%=intl._("Access Point")%>:</label>
         <% } /* streamrclient */ %>
            </div>
            <div id="portField" class="rowItem">
                <label for="port" accesskey="P">
                    <span class="accessKey">P</span>ort:
                    <% String value = editBean.getClientPort(curTunnel);
                       if (value == null || "".equals(value.trim())) {
                           out.write(" <font color=\"red\">(");
                           out.write(intl._("required"));
                           out.write(")</font>");
                       }
                     %>
                </label>
                <input type="text" size="6" maxlength="5" id="port" name="port" title="Access Port Number" value="<%=editBean.getClientPort(curTunnel)%>" class="freetext" />               
            </div>
            <div id="reachField" class="rowItem">
                <label for="reachableBy" accesskey="r">
         <%
            if ("streamrclient".equals(tunnelType)) {
                       out.write("Host:");
                       String targetHost = editBean.getTargetHost(curTunnel);
                       if (targetHost == null || "".equals(targetHost.trim())) {
                           out.write(" <font color=\"red\">(");
                           out.write(intl._("required"));
                           out.write(")</font>");
                       }   
          %>
                </label>
                <input type="text" size="20" id="targetHost" name="targetHost" title="Target Hostname or IP" value="<%=targetHost%>" class="freetext" />                
         <% } else { %>
                    <%=intl._("Reachable by")%>(<span class="accessKey">R</span>):
                </label>
                <select id="reachableBy" name="reachableBy" title="IP for Client Access" class="selectbox">
              <%
                    String clientInterface = editBean.getClientInterface(curTunnel);
                    for (String ifc : editBean.interfaceSet()) {
                        out.write("<option value=\"");
                        out.write(ifc);
                        out.write('\"');
                        if (ifc.equals(clientInterface))
                            out.write(" selected=\"selected\"");
                        out.write('>');
                        out.write(ifc);
                        out.write("</option>\n");
                    }
              %>
                </select>                
         <% } /* streamrclient */ %>
            </div> 
         <% if ("client".equals(tunnelType) || "ircclient".equals(tunnelType)) {
          %><div id="portField" class="rowItem">
                <label>
                    <%=intl._("Use SSL?")%>
                </label>
                <input value="1" type="checkbox" id="startOnLoad" name="useSSL" title="Clients use SSL to connect" <%=(editBean.isSSLEnabled(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
            </div>
         <% } /* tunnel types */ %>

            <div class="subdivider">
                <hr />
            </div>
           
            <% if ("httpclient".equals(tunnelType) || "connectclient".equals(tunnelType) || "sockstunnel".equals(tunnelType) || "socksirctunnel".equals(tunnelType)) {
          %><div id="destinationField" class="rowItem">
                <label for="proxyList" accesskey="x">
                    <%=intl._("Outproxies")%>(<span class="accessKey">x</span>):
                </label>
                <input type="text" size="30" id="proxyList" name="proxyList" title="List of Outproxy I2P destinations" value="<%=editBean.getClientDestination(curTunnel)%>" class="freetext" />                
            </div>
              <% if ("httpclient".equals(tunnelType)) {
                 %><div id="destinationField" class="rowItem">
                   <label>
                       <%=intl._("SSL Outproxies")%>:
                   </label>
                   <input type="text" size="30" id="sslProxyList" name="sslProxies" title="List of Outproxy I2P destinations" value="<%=editBean.getSslProxies(curTunnel)%>" class="freetext" />                
                   </div>
              <% }  // httpclient %>
            <div id="startupField" class="rowItem">
                <label>
                    <%=intl._("Use Outproxy Plugin")%>:
                </label>
                <input value="1" type="checkbox" id="shared" name="useOutproxyPlugin" title="Use plugin instead of above-listed proxies if available"<%=(editBean.getUseOutproxyPlugin(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
                <span class="comment"><%=intl._("(Check the Box for 'YES')")%></span>
            </div>
            <% } else if ("client".equals(tunnelType) || "ircclient".equals(tunnelType) || "streamrclient".equals(tunnelType)) {
          %><div id="destinationField" class="rowItem">
                <label for="targetDestination" accesskey="T">
                    <%=intl._("Tunnel Destination")%>(<span class="accessKey">T</span>):
                    <% String value2 = editBean.getClientDestination(curTunnel);
                       if (value2 == null || "".equals(value2.trim())) {
                           out.write(" <font color=\"red\">(");
                           out.write(intl._("required"));
                           out.write(")</font>");
                       }   
                     %>
                </label>
                <input type="text" size="30" id="targetDestination" name="targetDestination" title="Destination of the Tunnel" value="<%=editBean.getClientDestination(curTunnel)%>" class="freetext" />                
                <span class="comment">(<%=intl._("name, name:port, or destination")%>
                     <% if ("streamrclient".equals(tunnelType)) { /* deferred resolution unimplemented in streamr client */ %>
                         - <%=intl._("b32 not recommended")%>
                     <% } %> )
                </span>
            </div>
         <% } %>
         <% if (!"streamrclient".equals(tunnelType)) { %>
            <div id="sharedtField" class="rowItem">
                <label for="shared" accesskey="h">
                    <%=intl._("Shared Client")%>(<span class="accessKey">h</span>):
                </label>
                <input value="true" type="checkbox" id="shared" name="shared" title="Share tunnels with other clients"<%=(editBean.isSharedClient(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
                <span class="comment"><%=intl._("(Share tunnels with other clients and irc/httpclients? Change requires restart of client proxy)")%></span>
            </div>
         <% } // !streamrclient %>
            <div id="startupField" class="rowItem">
                <label for="startOnLoad" accesskey="a">
                    <%=intl._("Auto Start")%>(<span class="accessKey">A</span>):
                </label>
                <input value="1" type="checkbox" id="startOnLoad" name="startOnLoad" title="Start Tunnel Automatically"<%=(editBean.startAutomatically(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
                <span class="comment"><%=intl._("(Check the Box for 'YES')")%></span>
            </div>
         <% if ("ircclient".equals(tunnelType)) { %>
            <div id="startupField" class="rowItem">
                <label for="dcc" accesskey="d">
                    <%=intl._("Enable DCC")%>:
                </label>
                <input value="1" type="checkbox" id="startOnLoad" name="DCC" title="Enable DCC"<%=(editBean.getDCC(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
                <span class="comment"><%=intl._("(Check the Box for 'YES')")%></span>
            </div>
         <% } // ircclient %>
            
            <div class="footer">
            </div>
        </div>

        <div id="tunnelAdvancedNetworking" class="panel">
            <div class="header">
                <h4><%=intl._("Advanced networking options")%></h4><br />
                <span class="comment"><%=intl._("(NOTE: when this client proxy is configured to share tunnels, then these options are for all the shared proxy clients!)")%></span>
            </div>

            <div class="separator">
                <hr />
            </div>
            
            <div id="tunnelOptionsField" class="rowItem">
                <label><%=intl._("Tunnel Options")%>:</label>
            </div>
            <div id="depthField" class="rowItem">
                <label for="tunnelDepth" accesskey="t">
                    <%=intl._("Length")%>(<span class="accessKey">t</span>):
                </label>
                <select id="tunnelDepth" name="tunnelDepth" title="Length of each Tunnel" class="selectbox">
                    <% int tunnelDepth = editBean.getTunnelDepth(curTunnel, 3);
                  %><option value="0"<%=(tunnelDepth == 0 ? " selected=\"selected\"" : "") %>><%=intl._("0 hop tunnel (no anonymity)")%></option>
                    <option value="1"<%=(tunnelDepth == 1 ? " selected=\"selected\"" : "") %>><%=intl._("1 hop tunnel (low anonymity)")%></option>
                    <option value="2"<%=(tunnelDepth == 2 ? " selected=\"selected\"" : "") %>><%=intl._("2 hop tunnel (medium anonymity)")%></option>
                    <option value="3"<%=(tunnelDepth == 3 ? " selected=\"selected\"" : "") %>><%=intl._("3 hop tunnel (high anonymity)")%></option>
                <% if (tunnelDepth > 3) { 
                %>    <option value="<%=tunnelDepth%>" selected="selected"><%=tunnelDepth%> <%=intl._("hop tunnel (very poor performance)")%></option>
                <% }
              %></select>
            </div>
            <div id="varianceField" class="rowItem">
                <label for="tunnelVariance" accesskey="v">
                    <%=intl._("Variance")%>(<span class="accessKey">V</span>):
                </label>
                <select id="tunnelVariance" name="tunnelVariance" title="Level of Randomization for Tunnel Length" class="selectbox">
                    <% int tunnelVariance = editBean.getTunnelVariance(curTunnel, 0);
                  %><option value="0"<%=(tunnelVariance  ==  0 ? " selected=\"selected\"" : "") %>><%=intl._("0 hop variance (no randomisation, consistant performance)")%></option>
                    <option value="1"<%=(tunnelVariance  ==  1 ? " selected=\"selected\"" : "") %>><%=intl._("+ 0-1 hop variance (medium additive randomisation, subtractive performance)")%></option>
                    <option value="2"<%=(tunnelVariance  ==  2 ? " selected=\"selected\"" : "") %>><%=intl._("+ 0-2 hop variance (high additive randomisation, subtractive performance)")%></option>
                    <option value="-1"<%=(tunnelVariance == -1 ? " selected=\"selected\"" : "") %>><%=intl._("+/- 0-1 hop variance (standard randomisation, standard performance)")%></option>
                    <option value="-2"<%=(tunnelVariance == -2 ? " selected=\"selected\"" : "") %>><%=intl._("+/- 0-2 hop variance (not recommended)")%></option>
                <% if (tunnelVariance > 2 || tunnelVariance < -2) {
                %>    <option value="<%=tunnelVariance%>" selected="selected"><%= (tunnelVariance > 2 ? "+ " : "+/- ") %>0-<%=tunnelVariance%> <%=intl._("hop variance")%></option>
                <% }
              %></select>                
            </div>                
            <div id="countField" class="rowItem">
                <label for="tunnelQuantity" accesskey="C">
                    <%=intl._("Count")%>(<span class="accessKey">C</span>):
                </label>
                <select id="tunnelQuantity" name="tunnelQuantity" title="Number of Tunnels in Group" class="selectbox">
                    <%=editBean.getQuantityOptions(curTunnel)%>
                </select>                
            </div>
            <div id="backupField" class="rowItem">
                <label for="tunnelBackupQuantity" accesskey="b">
                    <%=intl._("Backup Count")%>(<span class="accessKey">B</span>):
                </label>
                <select id="tunnelBackupQuantity" name="tunnelBackupQuantity" title="Number of Reserve Tunnels" class="selectbox">
                    <% int tunnelBackupQuantity = editBean.getTunnelBackupQuantity(curTunnel, 0);
                  %><option value="0"<%=(tunnelBackupQuantity == 0 ? " selected=\"selected\"" : "") %>><%=intl._("0 backup tunnels (0 redundancy, no added resource usage)")%></option>
                    <option value="1"<%=(tunnelBackupQuantity == 1 ? " selected=\"selected\"" : "") %>><%=intl._("1 backup tunnel each direction (low redundancy, low resource usage)")%></option>
                    <option value="2"<%=(tunnelBackupQuantity == 2 ? " selected=\"selected\"" : "") %>><%=intl._("2 backup tunnels each direction (medium redundancy, medium resource usage)")%></option>
                    <option value="3"<%=(tunnelBackupQuantity == 3 ? " selected=\"selected\"" : "") %>><%=intl._("3 backup tunnels each direction (high redundancy, high resource usage)")%></option>
                <% if (tunnelBackupQuantity > 3) {
                %>    <option value="<%=tunnelBackupQuantity%>" selected="selected"><%=tunnelBackupQuantity%> <%=intl._("backup tunnels")%></option>
                <% }
              %></select>                
            </div>
                            
            <div class="subdivider">
                <hr />
            </div>

         <% if (!"streamrclient".equals(tunnelType)) { %>
            <div id="profileField" class="rowItem">
                <label for="profile" accesskey="f">
                    <%=intl._("Profile")%>(<span class="accessKey">f</span>):
                </label>
                <select id="profile" name="profile" title="Connection Profile" class="selectbox">
                    <% boolean interactiveProfile = editBean.isInteractive(curTunnel);
                  %><option <%=(interactiveProfile == true  ? "selected=\"selected\" " : "")%>value="interactive"><%=intl._("interactive connection")%> </option>
                    <option <%=(interactiveProfile == false ? "selected=\"selected\" " : "")%>value="bulk"><%=intl._("bulk connection (downloads/websites/BT)")%> </option>
                </select>                
            </div>
            <div id="delayConnectField" class="rowItem">
                <label for="connectDelay" accesskey="y">
                    <%=intl._("Delay Connect")%>(<span class="accessKey">y</span>):
                </label>
                <input value="1000" type="checkbox" id="connectDelay" name="connectDelay" title="Delay Connection"<%=(editBean.shouldDelay(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
                <span class="comment">(<%=intl._("for request/response connections")%>)</span>
            </div>

            <div class="subdivider">
                <hr />
            </div>
         <% } // !streamrclient %>

            <div id="optionsField" class="rowItem">
                <label><%=intl._("Router I2CP Address")%>:</label>
            </div>
            <div id="optionsHostField" class="rowItem">
                <label for="clientHost" accesskey="o">
                    <%=intl._("Host")%>(<span class="accessKey">o</span>):
                </label>
                <input type="text" id="clientHost" name="clientHost" size="20" title="I2CP Hostname or IP" value="<%=editBean.getI2CPHost(curTunnel)%>" class="freetext" <% if (editBean.isRouterContext()) { %> readonly="readonly" <% } %> />                
            </div>
            <div id="optionsPortField" class="rowItem">
                <label for="clientPort" accesskey="r">
                    <%=intl._("Port")%>(<span class="accessKey">r</span>):
                </label>
                <input type="text" id="clientPort" name="clientport" size="20" title="I2CP Port Number" value="<%=editBean.getI2CPPort(curTunnel)%>" class="freetext" <% if (editBean.isRouterContext()) { %> readonly="readonly" <% } %> />                
            </div>
                 
         <% if (!"streamrclient".equals(tunnelType)) { // streamr client sends pings so it will never be idle %>
            <div class="subdivider">
                <hr />
            </div>

            <div id="optionsField" class="rowItem">
                <label for="reduce" accesskey="c">
                    <%=intl._("Delay tunnel open until required")%>(<span class="accessKey">D</span>):
                </label>
            </div>
            <div id="portField" class="rowItem">
                <label for="access" accesskey="c">
                    <%=intl._("Enable")%>:
                </label>
                <input value="1" type="checkbox" id="startOnLoad" name="delayOpen" title="Delay Tunnel Open"<%=(editBean.getDelayOpen(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
            </div>
         <% } // !streamrclient %>
                 
            <div class="subdivider">
                <hr />
            </div>
           
            <div id="optionsField" class="rowItem">
                <label for="reduce" accesskey="d">
                    <%=intl._("Reduce tunnel quantity when idle")%>(<span class="accessKey">d</span>):
                </label>
            </div>
            <div id="portField" class="rowItem">
                <label for="access" accesskey="d">
                    <%=intl._("Enable")%>:
                </label>
                <input value="1" type="checkbox" id="startOnLoad" name="reduce" title="Reduce Tunnels"<%=(editBean.getReduce(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
            </div>
            <div id="portField" class="rowItem">
                <label for="reduceCount" accesskey="d">
                    <%=intl._("Reduced tunnel count")%>:
                </label>
                <input type="text" id="port" name="reduceCount" size="1" maxlength="1" title="Reduced Tunnel Count" value="<%=editBean.getReduceCount(curTunnel)%>" class="freetext" />                
            </div>
            <div id="portField" class="rowItem">
                <label for="reduceTime" accesskey="d">
                    <%=intl._("Idle minutes")%>:
                </label>
                <input type="text" id="port" name="reduceTime" size="4" maxlength="4" title="Reduced Tunnel Idle Time" value="<%=editBean.getReduceTime(curTunnel)%>" class="freetext" />                
            </div>
            
            <div class="subdivider">
                <hr />
            </div>
           
            <div id="optionsField" class="rowItem">
                <label for="reduce" accesskey="c">
                    <%=intl._("Close tunnels when idle")%>(<span class="accessKey">C</span>):
                </label>
            </div>
            <div id="portField" class="rowItem">
                <label for="access" accesskey="c">
                    <%=intl._("Enable")%>:
                </label>
                <input value="1" type="checkbox" id="startOnLoad" name="close" title="Close Tunnels"<%=(editBean.getClose(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
            </div>
            <div id="portField" class="rowItem">
                <label for="access" accesskey="c">
                    <%=intl._("New Keys on Reopen")%>:
                </label>
                <table border="0"><tr><!-- I give up -->
                <td><input value="1" type="radio" id="startOnLoad" name="newDest" title="New Destination"
                     <%=(editBean.getNewDest(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" /></td>
                <td><%=intl._("Enable")%></td>
                <td><input value="0" type="radio" id="startOnLoad" name="newDest" title="New Destination"
                     <%=(editBean.getNewDest(curTunnel) || editBean.getPersistentClientKey(curTunnel) ? "" : " checked=\"checked\"")%> class="tickbox" /></td>
                <td><%=intl._("Disable")%></td></tr>
                </table>
            </div>
            <div id="portField" class="rowItem">
                <label for="reduceTime" accesskey="c">
                    <%=intl._("Idle minutes")%>:
                </label>
                <input type="text" id="port" name="closeTime" size="4" maxlength="4" title="Close Tunnel Idle Time" value="<%=editBean.getCloseTime(curTunnel)%>" class="freetext" />                
            </div>
                 
            <div class="subdivider">
                <hr />
            </div>

         <% if ("client".equals(tunnelType) || "ircclient".equals(tunnelType) || "socksirctunnel".equals(tunnelType) || "sockstunnel".equals(tunnelType)) { %>
            <div id="optionsField" class="rowItem">
                <label for="privKeyFile" accesskey="k">
                    <%=intl._("Persistent private key")%>(<span class="accessKey">k</span>):
                </label>
            </div>
            <div id="portField" class="rowItem">
                <label><%=intl._("Enable")%>:</label>
                <input value="2" type="radio" id="startOnLoad" name="newDest" title="New Destination"
                     <%=(editBean.getPersistentClientKey(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
            </div>
            <div id="reachField" class="rowItem">
                <label><%=intl._("File")%>:</label>
                <input type="text" size="30" id="clientHost" name="privKeyFile" title="Path to Private Key File" value="<%=editBean.getPrivateKeyFile(curTunnel)%>" class="freetext" />               
            </div>
         <%
            String destb64 = editBean.getDestinationBase64(curTunnel);     
            if (destb64.length() > 0) {
           %>   <div id="destinationField" class="rowItem">
                    <label for="localDestination" accesskey="L">
                        <%=intl._("Local destination")%>(<span class="accessKey">L</span>):
                    </label>
                    <textarea rows="1" style="height: 3em;" cols="60" readonly="readonly" id="localDestination" title="Read Only: Local Destination (if known)" wrap="off" spellcheck="false"><%=destb64%></textarea>               
                </div>
                <div id="destinationField" class="rowItem">
                    <label><%=intl._("Local Base 32")%>:</label>
                    <%=editBean.getDestHashBase32(curTunnel)%>
                </div>
         <% } // if destb64  %>

            <div class="subdivider">
                <hr />
            </div>
         <% } %>

         <% if ("httpclient".equals(tunnelType)) { %>
            <div id="optionsField" class="rowItem">
                <label><%=intl._("Pass User-Agent header through")%>:</label>
            </div>
            <div id="portField" class="rowItem">
                <label><%=intl._("Enable")%>:</label>
                <input value="1" type="checkbox" id="startOnLoad" name="allowUserAgent" title="Do not spoof user agent string when checked"<%=(editBean.getAllowUserAgent(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
            </div><br />
            <div id="optionsField" class="rowItem">
                <label><%=intl._("Pass Referer header through")%>:</label>
            </div>
            <div id="portField" class="rowItem">
                <label><%=intl._("Enable")%>:</label>
                <input value="1" type="checkbox" id="startOnLoad" name="allowReferer" title="Do not block referer header when checked"<%=(editBean.getAllowReferer(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
            </div><br />
            <div id="optionsField" class="rowItem">
                <label><%=intl._("Pass Accept headers through")%>:</label>
            </div>
            <div id="portField" class="rowItem">
                <label><%=intl._("Enable")%>:</label>
                <input value="1" type="checkbox" id="startOnLoad" name="allowAccept" title="Do not block accept headers when checked"<%=(editBean.getAllowAccept(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
            </div><br />
            <div id="optionsField" class="rowItem">
                <label><%=intl._("Allow SSL to I2P addresses")%>:</label>
            </div>
            <div id="portField" class="rowItem">
                <label><%=intl._("Enable")%>:</label>
                <input value="1" type="checkbox" id="startOnLoad" name="allowInternalSSL" title="Allow SSL to I2P addresses when checked"<%=(editBean.getAllowInternalSSL(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
            </div>
            <div class="subdivider">
                <hr />
            </div>
         <% } // if httpclient %>
           
         <% if (true /* editBean.isAdvanced() */ ) {
                int currentSigType = editBean.getSigType(curTunnel, tunnelType);
           %>
            <div id="tunnelOptionsField" class="rowItem">
                <label>
                    <%=intl._("Signature type")%>
                    (<%=intl._("Experts only!")%>)
                </label>
            </div>
            <div id="hostField" class="rowItem">
              <div id="portField" class="rowItem">
                <label>DSA-SHA1</label>
                <input value="0" type="radio" id="startOnLoad" name="sigType" title="Default"<%=(currentSigType==0 ? " checked=\"checked\"" : "")%> class="tickbox" />                
              </div>
           <% if (editBean.isSigTypeAvailable(1)) { %>
              <div id="portField" class="rowItem">
                <label>ECDSA-P256</label>
                <input value="1" type="radio" id="startOnLoad" name="sigType" title="Advanced users only"<%=(currentSigType==1 ? " checked=\"checked\"" : "")%> class="tickbox" />                
              </div>
           <% }
              if (editBean.isSigTypeAvailable(2)) { %>
              <div id="portField" class="rowItem">
                <label>ECDSA-P384</label>
                <input value="2" type="radio" id="startOnLoad" name="sigType" title="Advanced users only"<%=(currentSigType==2 ? " checked=\"checked\"" : "")%> class="tickbox" />                
              </div>
           <% }
              if (editBean.isSigTypeAvailable(3)) { %>
              <div id="portField" class="rowItem">
                <label>ECDSA-P521</label>
                <input value="3" type="radio" id="startOnLoad" name="sigType" title="Advanced users only"<%=(currentSigType==3 ? " checked=\"checked\"" : "")%> class="tickbox" />                
              </div>
           <% }
              if (editBean.isSigTypeAvailable(7)) { %>
              <div id="portField" class="rowItem">
                <label>Ed25519-SHA-512</label>
                <input value="7" type="radio" id="startOnLoad" name="sigType" title="Advanced users only"<%=(currentSigType==7 ? " checked=\"checked\"" : "")%> class="tickbox" />                
              </div>
           <% }   // isAvailable %>
            </div>
                 
            <div class="subdivider">
                <hr />
            </div>
         <% } // isAdvanced %>

         <% if ("httpclient".equals(tunnelType) || "connectclient".equals(tunnelType) || "sockstunnel".equals(tunnelType) || "socksirctunnel".equals(tunnelType)) { %>
            <div id="accessField" class="rowItem">
                <label><%=intl._("Local Authorization")%>:</label>
            </div>
            <div id="portField" class="rowItem">
                <label>
                    <%=intl._("Enable")%>:
                </label>
                <input value="1" type="checkbox" id="startOnLoad" name="proxyAuth" title="Check to require authorization for this service"<%=(editBean.getProxyAuth(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
            </div>
            <div id="portField" class="rowItem">
                <label>
                    <%=intl._("Username")%>:
                </label>
                <input type="text" id="clientPort" name="proxyUsername" title="Set username for this service" value="" class="freetext" />                
            </div>
            <div id="portField" class="rowItem">
                <label>
                    <%=intl._("Password")%>:
                </label>
                <input type="password" id="clientPort" name="nofilter_proxyPassword" title="Set password for this service" value="" class="freetext" />                
            </div>
            <div class="subdivider">
                <hr />
            </div>
            <div id="accessField" class="rowItem">
                <label><%=intl._("Outproxy Authorization")%>:</label>
            </div>
            <div id="portField" class="rowItem">
                <label>
                    <%=intl._("Enable")%>:
                </label>
                <input value="1" type="checkbox" id="startOnLoad" name="outproxyAuth" title="Check if the outproxy requires authorization"<%=(editBean.getOutproxyAuth(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
            </div>
            <div id="portField" class="rowItem">
                <label>
                    <%=intl._("Username")%>:
                </label>
                <input type="text" id="clientPort" name="outproxyUsername" title="Enter username required by outproxy" value="<%=editBean.getOutproxyUsername(curTunnel)%>" class="freetext" />                
            </div>
            <div id="portField" class="rowItem">
                <label>
                    <%=intl._("Password")%>:
                </label>
                <input type="password" id="clientPort" name="nofilter_outproxyPassword" title="Enter password required by outproxy" value="<%=editBean.getOutproxyPassword(curTunnel)%>" class="freetext" />                
            </div>
            <div class="subdivider">
                <hr />
            </div>
         <% } // httpclient || connect || socks || socksirc %>

         <% if ("httpclient".equals(tunnelType)) { %>
            <div id="optionsField" class="rowItem">
                <label><%=intl._("Jump URL List")%>:</label>
            </div>
            <div id="hostField" class="rowItem">
                <textarea rows="2" style="height: 8em;" cols="60" id="hostField" name="jumpList" title="List of helper URLs to offer when a host is not found in your addressbook" wrap="off" spellcheck="false"><%=editBean.getJumpList(curTunnel)%></textarea>               
            </div>
            <div class="subdivider">
                <hr />
            </div>
         <% } // httpclient %>

            <div id="customOptionsField" class="rowItem">
                <label for="customOptions" accesskey="u">
                    <%=intl._("Custom options")%>(<span class="accessKey">u</span>):
                </label>
                <input type="text" id="customOptions" name="nofilter_customOptions" size="60" title="Custom Options" value="<%=editBean.getCustomOptions(curTunnel)%>" class="freetext" spellcheck="false"/>                
            </div>
            
            <div class="footer">
            </div>
        </div>
        <div id="globalOperationsPanel" class="panel">
            <div class="header"></div>
            <div class="footer">
                <div class="toolbox">
                    <input type="hidden" value="true" name="removeConfirm" />
                    <button id="controlCancel" class="control" type="submit" name="action" value="" title="Cancel"><%=intl._("Cancel")%></button>
                    <button id="controlDelete" <%=(editBean.allowJS() ? "onclick=\"if (!confirm('Are you sure you want to delete?')) { return false; }\" " : "")%>accesskey="D" class="control" type="submit" name="action" value="Delete this proxy" title="Delete this Proxy"><%=intl._("Delete")%>(<span class="accessKey">D</span>)</button>
                    <button id="controlSave" accesskey="S" class="control" type="submit" name="action" value="Save changes" title="Save Changes"><%=intl._("Save")%>(<span class="accessKey">S</span>)</button>
                </div>
            </div> 
        </div>
    </form>
    <div id="pageFooter">
        </div>
<%

  } else {
     %>Tunnels are not initialized yet, please reload in two minutes.<%
  }  // isInitialized()

%>
    </body>
</html>
