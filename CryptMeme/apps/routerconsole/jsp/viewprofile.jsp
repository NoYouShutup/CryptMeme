<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("Peer Profile")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %>
<h1><%=intl._("Peer Profile")%></h1>
<div class="main" id="main"><div class="wideload">
<%
    String peerB64 = request.getParameter("peer");
    if (peerB64 == null || peerB64.length() <= 0 ||
        peerB64.replaceAll("[a-zA-Z0-9~=-]", "").length() != 0) {
        out.print("No peer specified");
    } else {

%>
<jsp:useBean id="stathelper" class="net.i2p.router.web.StatHelper" />
<jsp:setProperty name="stathelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<jsp:setProperty name="stathelper" property="peer" value="<%=peerB64%>" />
<% stathelper.storeWriter(out); %>
<h2><%=intl._("Profile for peer {0}", peerB64)%></h2>
<pre>
<jsp:getProperty name="stathelper" property="profile" />
</pre>
<%
    }
%>
</div></div></body></html>
