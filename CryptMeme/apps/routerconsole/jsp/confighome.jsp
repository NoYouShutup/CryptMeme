<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config home")%>
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
<h1><%=intl._("I2P Home Page Configuration")%></h1>
<div class="main" id="main">
<%@include file="confignav.jsi" %>

<jsp:useBean class="net.i2p.router.web.ConfigHomeHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<jsp:useBean class="net.i2p.router.web.HomeHelper" id="homehelper" scope="request" />
<jsp:setProperty name="homehelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />

<h3><%=intl._("Default Home Page")%></h3>
<form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="group" value="0">
 <input type="checkbox" name="oldHome" <jsp:getProperty name="homehelper" property="configHome" /> >
 <%=intl._("Use old home page")%>
 <input type="submit" name="action" class="accept" value="<%=intl._("Save")%>" >
</form>

<%
   if (homehelper.shouldShowSearch()) {
%>
<h3><%=intl._("Search Engines")%></h3>
<form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="group" value="3">
 <jsp:getProperty name="homehelper" property="configSearch" />
 <div class="formaction">
  <input type="submit" name="action" class="default" value="<%=intl._("Add item")%>" >
  <input type="submit" name="action" class="delete" value="<%=intl._("Delete selected")%>" >
  <input type="reset" class="cancel" value="<%=intl._("Cancel")%>" >
  <input type="submit" name="action" class="reload" value="<%=intl._("Restore defaults")%>" >
  <input type="submit" name="action" class="add" value="<%=intl._("Add item")%>" >
 </div>
</form>
<%
   }  // shouldShowSearch()
%>

<h3><%=intl._("Hidden Services of Interest")%></h3>
<form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="group" value="1">
 <jsp:getProperty name="homehelper" property="configFavorites" />
 <div class="formaction">
  <input type="submit" name="action" class="default" value="<%=intl._("Add item")%>" >
  <input type="submit" name="action" class="delete" value="<%=intl._("Delete selected")%>" >
  <input type="reset" class="cancel" value="<%=intl._("Cancel")%>" >
  <input type="submit" name="action" class="reload" value="<%=intl._("Restore defaults")%>" >
  <input type="submit" name="action" class="add" value="<%=intl._("Add item")%>" >
 </div>
</form>

<h3><%=intl._("Applications and Configuration")%></h3>
<form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="group" value="2">
 <jsp:getProperty name="homehelper" property="configServices" />
 <div class="formaction">
  <input type="submit" name="action" class="default" value="<%=intl._("Add item")%>" >
  <input type="submit" name="action" class="delete" value="<%=intl._("Delete selected")%>" >
  <input type="reset" class="cancel" value="<%=intl._("Cancel")%>" >
  <input type="submit" name="action" class="reload" value="<%=intl._("Restore defaults")%>" >
  <input type="submit" name="action" class="add" value="<%=intl._("Add item")%>" >
 </div>
</form>
</div></body></html>
