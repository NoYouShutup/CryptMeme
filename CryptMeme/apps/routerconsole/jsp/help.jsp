<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
  /*
   *   Do not tag this file for translation - copy it to help_xx.jsp and translate inline.
   */
%>
<html><head><title>I2P Router Console - help</title>
<%@include file="css.jsi" %>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %>
<h1>I2P Router Help &amp; Support</h1>
<div class="main" id="main"><p>

<%@include file="help.jsi" %>
<%@include file="help-legal.jsi" %>
<%@include file="help-sidebar.jsi" %>

<h2>Change Log</h2>
 <jsp:useBean class="net.i2p.router.web.ContentHelper" id="contenthelper" scope="request" />
 <% java.io.File fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getBaseDir(), "history.txt"); %>
 <jsp:setProperty name="contenthelper" property="page" value="<%=fpath.getAbsolutePath()%>" />
 <jsp:setProperty name="contenthelper" property="maxLines" value="256" />
 <jsp:setProperty name="contenthelper" property="startAtBeginning" value="true" />
 <jsp:getProperty name="contenthelper" property="textContent" />

 <p><a href="/history.txt">View the full change log</a>
 </p><hr></div></body></html>
