<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config update")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>
<h1><%=intl._("I2P Update Configuration")%></h1>
<div class="main" id="main">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigUpdateHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <jsp:useBean class="net.i2p.router.web.ConfigUpdateHelper" id="updatehelper" scope="request" />
 <jsp:setProperty name="updatehelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<div class="messages">
 <jsp:getProperty name="updatehelper" property="newsStatus" /></div>
<div class="configure">
 <form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <% /* set hidden default */ %>
 <input type="submit" name="action" value="" style="display:none" >
    <% if (updatehelper.canInstall()) { %>
      <h3><%=intl._("Check for I2P and news updates")%></h3>
      <div class="wideload"><table border="0" cellspacing="5">
        <tr><td colspan="2"></tr>
        <tr><td class="mediumtags" align="right"><b><%=intl._("News &amp; I2P Updates")%>:</b></td>
     <% } else { %>
      <h3><%=intl._("Check for news updates")%></h3>
      <div class="wideload"><table border="0" cellspacing="5">
        <tr><td colspan="2"></tr>
        <tr><td class="mediumtags" align="right"><b><%=intl._("News Updates")%>:</b></td>
     <% }   // if canInstall %>
          <td> <% if ("true".equals(System.getProperty("net.i2p.router.web.UpdateHandler.updateInProgress", "false"))) { %> <i><%=intl._("Update In Progress")%></i><br> <% } else { %> <input type="submit" name="action" class="check" value="<%=intl._("Check for updates")%>" />
            <% } %></td></tr>
        <tr><td colspan="2"><br></td></tr>
        <tr><td class="mediumtags" align="right"><b><%=intl._("News URL")%>:</b></td>
          <td><input type="text" size="60" name="newsURL" <% if (!updatehelper.isAdvanced()) { %>readonly="readonly"<% } %> value="<jsp:getProperty name="updatehelper" property="newsURL" />"></td>
        </tr><tr><td class="mediumtags" align="right"><b><%=intl._("Refresh frequency")%>:</b>
          <td><jsp:getProperty name="updatehelper" property="refreshFrequencySelectBox" /></td></tr>
    <% if (updatehelper.canInstall()) { %>
        <tr><td class="mediumtags" align="right"><b><%=formhandler._("Update policy")%>:</b></td>
          <td><jsp:getProperty name="updatehelper" property="updatePolicySelectBox" /></td></tr>
    <% }   // if canInstall %>
        <tr><td class="mediumtags" align="right"><b><%=intl._("Fetch news through the eepProxy?")%></b></td>
          <td><jsp:getProperty name="updatehelper" property="newsThroughProxy" /></td></tr>
        <tr><td class="mediumtags" align="right"><b><%=intl._("Update through the eepProxy?")%></b></td>
          <td><jsp:getProperty name="updatehelper" property="updateThroughProxy" /></td></tr>
      <% if (updatehelper.isAdvanced()) { %>
        <tr><td class="mediumtags" align="right"><b><%=intl._("eepProxy host")%>:</b></td>
          <td><input type="text" size="10" name="proxyHost" value="<jsp:getProperty name="updatehelper" property="proxyHost" />" /></td>
        </tr><tr><td class="mediumtags" align="right"><b><%=intl._("eepProxy port")%>:</b></td>
          <td><input type="text" size="10" name="proxyPort" value="<jsp:getProperty name="updatehelper" property="proxyPort" />" /></td></tr>
      <% }   // if isAdvanced %>
    <% if (updatehelper.canInstall()) { %>
      <% if (updatehelper.isAdvanced()) { %>
        <tr><td class="mediumtags" align="right"><b><%=intl._("Update URLs")%>:</b></td>
          <td><textarea cols="60" rows="6" name="updateURL" wrap="off" spellcheck="false"><jsp:getProperty name="updatehelper" property="updateURL" /></textarea></td>
        </tr><tr><td class="mediumtags" align="right"><b><%=intl._("Trusted keys")%>:</b></td>
          <td><textarea cols="60" rows="6" name="trustedKeys" wrap="off" spellcheck="false"><jsp:getProperty name="updatehelper" property="trustedKeys" /></textarea></td></tr>
        <tr><td id="devSU3build" class="mediumtags" align="right"><b><%=intl._("Update with signed development builds?")%></b></td>
          <td><jsp:getProperty name="updatehelper" property="updateDevSU3" /></td>
        </tr><tr><td class="mediumtags" align="right"><b><%=intl._("Signed Build URL")%>:</b></td>
          <td><input type="text" size="60" name="devSU3URL" value="<jsp:getProperty name="updatehelper" property="devSU3URL" />"></td></tr>
        <tr><td id="unsignedbuild" class="mediumtags" align="right"><b><%=intl._("Update with unsigned development builds?")%></b></td>
          <td><jsp:getProperty name="updatehelper" property="updateUnsigned" /></td>
        </tr><tr><td class="mediumtags" align="right"><b><%=intl._("Unsigned Build URL")%>:</b></td>
          <td><input type="text" size="60" name="zipURL" value="<jsp:getProperty name="updatehelper" property="zipURL" />"></td></tr>
      <% }   // if isAdvanced %>
    <% } else { %>
        <tr><td class="mediumtags" align="center" colspan="2"><b><%=intl._("Updates will be dispatched via your package manager.")%></b></td></tr>
    <% }   // if canInstall %>
        <tr class="tablefooter"><td colspan="2">
        <div class="formaction">
            <input type="reset" class="cancel" value="<%=intl._("Cancel")%>" >
            <input type="submit" name="action" class="accept" value="<%=intl._("Save")%>" >
        </div></td></tr></table></div></form></div></div></body></html>
