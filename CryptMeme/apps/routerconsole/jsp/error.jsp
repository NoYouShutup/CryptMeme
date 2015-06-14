<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
    // Let's make this easy...
    // These are defined in Jetty 7 org.eclipse.jetty.server.Dispatcher,
    // and in Servlet 3.0 (Jetty 8) javax.servlet.RequestDispatcher,
    // just use the actual strings here to make it compatible with either
    final Integer ERROR_CODE = (Integer) request.getAttribute("javax.servlet.error.status_code");
    final String ERROR_URI = (String) request.getAttribute("javax.servlet.error.request_uri");
    final String ERROR_MESSAGE = (String) request.getAttribute("javax.servlet.error.message");
    if (ERROR_CODE != null && ERROR_MESSAGE != null) {
        // this is deprecated but we don't want sendError()
        response.setStatus(ERROR_CODE.intValue(), ERROR_MESSAGE);
    }
    // If it can't find the iframe or viewtheme.jsp I wonder if the whole thing blows up...
%>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("Page Not Found")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %>
<h1><%=ERROR_CODE%>&nbsp;<%=ERROR_MESSAGE%></h1>
<div class="sorry" id="warning">
<%=intl._("Sorry! You appear to be requesting a non-existent Router Console page or resource.")%><hr>
<%=intl._("Error 404")%>: <%=ERROR_URI%>&nbsp;<%=intl._("not found")%>.
</div></body></html>
