<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("home")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">
<%
    String consoleNonce = intl.getNonce();
%>

<%@include file="summary.jsi" %>

<h1><%=intl._("I2P Router Console")%></h1>
<div class="news" id="news">
<%
   if (newshelper.shouldShowNews()) {
%>
 <jsp:getProperty name="newshelper" property="content" />
 <hr>
<%
   }  // shouldShowNews()
%>
 <jsp:useBean class="net.i2p.router.web.ConfigUpdateHelper" id="updatehelper" scope="request" />
 <jsp:setProperty name="updatehelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
 <jsp:getProperty name="updatehelper" property="newsStatus" /><br>
</div><div class="main" id="main">
 <jsp:useBean class="net.i2p.router.web.ContentHelper" id="contenthelper" scope="request" />
 <div class="welcome">
  <div class="langbox"> <% /* English, then alphabetical by English name please */ %>
    <a href="/console?lang=en&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=us" title="English" alt="English"></a>
    <a href="/console?lang=ar&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=lang_ar" title="عربية" alt="عربية"></a>
    <a href="/console?lang=zh&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=cn" title="中文" alt="中文"></a>
    <a href="/console?lang=cs&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=cz" title="čeština" alt="čeština"></a>
    <a href="/console?lang=da&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=dk" title="Dansk" alt="Dansk"></a>
    <a href="/console?lang=de&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=de" title="Deutsch" alt="Deutsch"></a>
    <a href="/console?lang=et&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=ee" title="Eesti" alt="Eesti"></a>
    <a href="/console?lang=es&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=es" title="Español" alt="Español"></a>
    <a href="/console?lang=fi&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=fi" title="Suomi" alt="Suomi"></a>
    <a href="/console?lang=fr&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=fr" title="Français" alt="Français"></a>
    <a href="/console?lang=el&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=gr" title="ελληνικά" alt="ελληνικά"></a>
    <a href="/console?lang=hu&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=hu" title="Magyar" alt="Magyar"></a>
    <a href="/console?lang=it&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=it" title="Italiano" alt="Italiano"></a>
    <a href="/console?lang=ja&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=jp" title="日本語" alt="日本語"></a><br>
    <a href="/console?lang=mg&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=mg" title="Malagasy" alt="Malagasy"></a>
    <a href="/console?lang=nb&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=no" title="Bokmål" alt="Norwegian Bokmaal"></a>
    <a href="/console?lang=nl&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=nl" title="Nederlands" alt="Nederlands"></a>
    <a href="/console?lang=pl&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=pl" title="Polski" alt="Polski"></a>
    <a href="/console?lang=pt&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=pt" title="Português" alt="Português"></a>
    <a href="/console?lang=pt_BR&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=br" title="Português brasileiro" alt="Português brasileiro"></a>
    <a href="/console?lang=ro&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=ro" title="Română" alt="Română"></a>
    <a href="/console?lang=ru&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=ru" title="Русский" alt="Русский"></a>
    <a href="/console?lang=sk&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=sk" title="Slovenčina" alt="Slovenčina"></a>
    <a href="/console?lang=sv&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=se" title="Svenska" alt="Svenska"></a>
    <a href="/console?lang=tr&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=tr" title="Türkçe" alt="Türkçe"></a>
    <a href="/console?lang=uk&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=ua" title="Українська" alt="Українська"></a>
    <a href="/console?lang=vi&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=vn" title="Tiếng Việt" alt="Tiếng Việt"></a>
  </div>
  <a name="top"></a>
  <h2><%=intl._("Welcome to I2P")%></h2>
 </div>
 <% java.io.File fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getBaseDir(), "docs/readme.html"); %>
 <jsp:setProperty name="contenthelper" property="page" value="<%=fpath.getAbsolutePath()%>" />
 <jsp:setProperty name="contenthelper" property="maxLines" value="300" />
 <jsp:setProperty name="contenthelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
 <jsp:getProperty name="contenthelper" property="content" />
</div></body></html>
