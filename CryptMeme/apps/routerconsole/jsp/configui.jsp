<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config UI")%>
<style type='text/css'>
input.default {
    width: 1px;
    height: 1px;
    visibility: hidden;
}
</style>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>

<jsp:useBean class="net.i2p.router.web.ConfigUIHelper" id="uihelper" scope="request" />
<jsp:setProperty name="uihelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />

<h1><%=uihelper._("I2P UI Configuration")%></h1>
<div class="main" id="main">

 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigUIHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<div class="configure"><div class="topshimten"><h3><%=uihelper._("Router Console Theme")%></h3></div>
 <form action="" method="POST">
 <input type="hidden" name="consoleNonce" value="<%=intl.getNonce()%>" >
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="action" value="blah" >
<%
 String userAgent = request.getHeader("User-Agent");
 if (userAgent == null || userAgent.contains("Trident/6") || !userAgent.contains("MSIE")) {
%>
 <jsp:getProperty name="uihelper" property="settings" />
<% } else { %>
<%=uihelper._("Theme selection disabled for Internet Explorer, sorry.")%>
<hr>
<%=uihelper._("If you're not using IE, it's likely that your browser is pretending to be IE; please configure your browser (or proxy) to use a different User Agent string if you'd like to access the console themes.")%>
<% } %>
 <jsp:getProperty name="uihelper" property="forceMobileConsole" />
<h3><%=uihelper._("Router Console Language")%></h3>
<jsp:getProperty name="uihelper" property="langSettings" />
<p><%=uihelper._("Please contribute to the router console translation project! Contact the developers in #i2p-dev on IRC to help.")%>
</p><hr><div class="formaction">
<input type="reset" class="cancel" value="<%=intl._("Cancel")%>" >
<input type="submit" name="shouldsave" class="accept" value="<%=intl._("Apply")%>" >
</div></form>

<h3><%=uihelper._("Router Console Password")%></h3>
<form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <jsp:getProperty name="uihelper" property="passwordForm" />
 <div class="formaction">
  <input type="submit" name="action" class="default" value="<%=intl._("Add user")%>" >
  <input type="submit" name="action" class="delete" value="<%=intl._("Delete selected")%>" >
  <input type="reset" class="cancel" value="<%=intl._("Cancel")%>" >
  <input type="submit" name="action" class="add" value="<%=intl._("Add user")%>" >
 </div>
</form></div>
</div></body></html>
