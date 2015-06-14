<%
    // NOTE: Do the header carefully so there is no whitespace before the <?xml... line

    response.setHeader("X-Frame-Options", "SAMEORIGIN");
    // edit pages need script for the delete button 'are you sure'
    response.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'");
    response.setHeader("X-XSS-Protection", "1; mode=block");

%><%@page pageEncoding="UTF-8"
%><%@page trimDirectiveWhitespaces="true"
%><%@page contentType="text/html" import="net.i2p.i2ptunnel.web.EditBean"
%><% 
String tun = request.getParameter("tunnel");
if (tun != null) {
  try {
    int curTunnel = Integer.parseInt(tun);
    if (EditBean.staticIsClient(curTunnel)) {
        %><jsp:include page="editClient.jsp" /><%
    } else {
        %><jsp:include page="editServer.jsp" /><%
    }
  } catch (NumberFormatException nfe) {
    %>Invalid tunnel parameter<%
  }
} else {
  String type = request.getParameter("type");
  int curTunnel = -1;
  if (EditBean.isClient(type)) {
        %><jsp:include page="editClient.jsp" /><%
  } else {
        %><jsp:include page="editServer.jsp" /><%
  }
}
%>
