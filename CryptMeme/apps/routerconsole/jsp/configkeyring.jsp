<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config keyring")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>
<h1><%=intl._("I2P Keyring Configuration")%></h1>
<div class="main" id="main">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigKeyringHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <jsp:useBean class="net.i2p.router.web.ConfigKeyringHelper" id="keyringhelper" scope="request" />
 <jsp:setProperty name="keyringhelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<div class="configure"><h2><%=intl._("Keyring")%></h2><p>
 <%=intl._("The router keyring is used to decrypt encrypted leaseSets.")%>
 <%=intl._("The keyring may contain keys for local or remote encrypted destinations.")%></p>
 <div class="wideload">
 <jsp:getProperty name="keyringhelper" property="summary" />
</div>

 <form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <h3><%=intl._("Manual Keyring Addition")%></h3><p>
 <%=intl._("Enter keys for encrypted remote destinations here.")%>
 <%=intl._("Keys for local destinations must be entered on the")%> <a href="i2ptunnel/"><%=intl._("I2PTunnel page")%></a>.
</p>
  <div class="wideload">
      <table><tr>
          <td class="mediumtags" align="right"><%=intl._("Dest. name, hash, or full key")%>:</td>
          <td><textarea name="peer" cols="44" rows="1" style="height: 3em;" wrap="off" spellcheck="false"></textarea></td>
        </tr><tr>
          <td class="mediumtags" align="right"><%=intl._("Encryption Key")%>:</td>
          <td><input type="text" size="55" name="key" ></td>
        </tr><tr>
          <td align="right" colspan="2">
<input type="reset" class="cancel" value="<%=intl._("Cancel")%>" >
<input type="submit" name="action" class="delete" value="<%=intl._("Delete key")%>" >
<input type="submit" name="action" class="add" value="<%=intl._("Add key")%>" >
</td></tr></table></div></form></div></div></body></html>
