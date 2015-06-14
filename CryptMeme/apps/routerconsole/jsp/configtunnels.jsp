<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config tunnels")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>

<jsp:useBean class="net.i2p.router.web.ConfigTunnelsHelper" id="tunnelshelper" scope="request" />
<jsp:setProperty name="tunnelshelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<h1><%=intl._("I2P Tunnel Configuration")%></h1>
<div class="main" id="main">
 <%@include file="confignav.jsi" %>
 <jsp:useBean class="net.i2p.router.web.ConfigTunnelsHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <div class="configure"><p>
 <%=intl._("NOTE")%>: 
 <%=intl._("The default settings work for most people.")%> 
 <%=intl._("There is a fundamental tradeoff between anonymity and performance.")%>
 <%=intl._("Tunnels longer than 3 hops (for example 2 hops + 0-2 hops, 3 hops + 0-1 hops, 3 hops + 0-2 hops), or a high quantity + backup quantity, may severely reduce performance or reliability.")%>
 <%=intl._("High CPU and/or high outbound bandwidth usage may result.")%>
 <%=intl._("Change these settings with care, and adjust them if you have problems.")%>
<div class="wideload">
<form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="action" value="blah" >
 <jsp:getProperty name="tunnelshelper" property="form" />
 <%=intl._("Note")%>: <%=intl._("Exploratory tunnel setting changes are stored in the router.config file.")%>
 <%=intl._("Client tunnel changes are temporary and are not saved.")%>
<%=intl._("To make permanent client tunnel changes see the")%> <a href="i2ptunnel/index.jsp"><%=intl._("i2ptunnel page")%></a>.
 <hr><div class="formaction">
<input type="reset" class="cancel" value="<%=intl._("Cancel")%>" >
<input type="submit" name="shouldsave" class="accept" value="<%=intl._("Save changes")%>" >
</div>
 </form></div></div></div></body></html>
