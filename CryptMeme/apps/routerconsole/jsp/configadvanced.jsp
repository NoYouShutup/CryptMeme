<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config advanced")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>

<jsp:useBean class="net.i2p.router.web.ConfigAdvancedHelper" id="advancedhelper" scope="request" />
<jsp:setProperty name="advancedhelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />

<h1><%=intl._("I2P Advanced Configuration")%></h1>
<div class="main" id="main">

 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigAdvancedHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <div class="configure">
 <div class="wideload">
<h3><%=intl._("Floodfill Configuration")%></h3>
<p><%=intl._("Floodill participation helps the network, but may use more of your computer's resources.")%></p>
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<input type="hidden" name="action" value="ff" >
<input type="radio" class="optbox" name="ff" value="auto" <%=advancedhelper.getFFChecked(2) %> >
<%=intl._("Automatic")%><br>
<input type="radio" class="optbox" name="ff" value="true" <%=advancedhelper.getFFChecked(1) %> >
<%=intl._("Force On")%><br>
<input type="radio" class="optbox" name="ff" value="false" <%=advancedhelper.getFFChecked(0) %> >
<%=intl._("Disable")%><br>
<div class="formaction">
<input type="submit" name="shouldsave" class="accept" value="<%=intl._("Save changes")%>" >
</div></form>
<h3><%=intl._("Advanced I2P Configuration")%></h3>
<% if (advancedhelper.isAdvanced()) { %>
 <form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="action" value="blah" >
<% }  // isAdvanced %>
 <textarea rows="32" cols="60" name="nofilter_config" wrap="off" spellcheck="false" <% if (!advancedhelper.isAdvanced()) { %>readonly="readonly"<% } %>><jsp:getProperty name="advancedhelper" property="settings" /></textarea><br><hr>
<% if (advancedhelper.isAdvanced()) { %>
      <div class="formaction">
        <input type="reset" class="cancel" value="<%=intl._("Cancel")%>" >
        <input type="submit" name="shouldsave" class="accept" value="<%=intl._("Save changes")%>" >
 <br><b><%=intl._("NOTE")%>:</b> <%=intl._("Some changes may require a restart to take effect.")%>
 </div></form>
<% } else { %>
<%=intl._("To make changes, edit the file {0}.", "<tt>" + advancedhelper.getConfigFileName() + "</tt>")%>
<% }  // isAdvanced %>
</div></div></div></body></html>
