/*
 * Created on 04.11.2004
 * 
 *  This file is part of susimail project, see http://susi.i2p/
 *  
 *  Copyright (C) 2004-2005  <susi23@mail.i2p>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *  
 * $Revision: 1.2 $
 */
package i2p.susi.webmail;

import i2p.susi.debug.Debug;
import i2p.susi.util.Config;
import i2p.susi.util.Folder;
import i2p.susi.util.ReadBuffer;
import i2p.susi.webmail.Messages;
import i2p.susi.webmail.encoding.DecodingException;
import i2p.susi.webmail.encoding.Encoding;
import i2p.susi.webmail.encoding.EncodingException;
import i2p.susi.webmail.encoding.EncodingFactory;
import i2p.susi.webmail.pop3.POP3MailBox;
import i2p.susi.webmail.smtp.SMTPClient;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.servlet.RequestWrapper;

/**
 * @author susi23
 */
public class WebMail extends HttpServlet
{
	/*
	 * set to true, if its a release build
	 */
	private static final boolean RELEASE;
	/*
	 * increase version number for every release
	 */
	private static final int version = 13;
	
	private static final long serialVersionUID = 1L;
	
	private static final String DEFAULT_HOST = "127.0.0.1";
	private static final int DEFAULT_POP3PORT = 7660;
	private static final int DEFAULT_SMTPPORT = 7659;
	
	private static final int STATE_AUTH = 1;
	private static final int STATE_LIST = 2;
	private static final int STATE_SHOW = 3;
	private static final int STATE_NEW = 4;
	private static final int STATE_CONFIG = 5;
	
	// TODO generate from servlet name to allow for renaming or multiple instances
	private static final String myself = "/susimail/susimail";
	
	/*
	 * form keys on login page
	 */
	private static final String LOGIN = "login";
	private static final String OFFLINE = "offline";
	private static final String USER = "user";
	private static final String PASS = "pass";
	private static final String HOST = "host";
	private static final String POP3 = "pop3";
	private static final String SMTP = "smtp";
	
	/*
	 * button names
	 */
	private static final String LOGOUT = "logout";
	private static final String RELOAD = "reload";
	private static final String SAVE = "save";
	private static final String SAVE_AS = "saveas";
	private static final String REFRESH = "refresh";
	private static final String CONFIGURE = "configure";
	private static final String NEW = "new";
	private static final String REPLY = "reply";
	private static final String REPLYALL = "replyall";
	private static final String FORWARD = "forward";
	private static final String DELETE = "delete";
	private static final String REALLYDELETE = "really_delete";
	private static final String SHOW = "show";
	private static final String DOWNLOAD = "download";
	private static final String RAW_ATTACHMENT = "att";
	
	private static final String MARKALL = "markall";
	private static final String CLEAR = "clearselection";
	private static final String INVERT = "invertselection";
	
	private static final String PREVPAGE = "prevpage";
	private static final String NEXTPAGE = "nextpage";
	private static final String FIRSTPAGE = "firstpage";
	private static final String LASTPAGE = "lastpage";
	private static final String PAGESIZE = "pagesize";
	private static final String SETPAGESIZE = "setpagesize";
	
	private static final String SEND = "send";
	private static final String CANCEL = "cancel";
	private static final String DELETE_ATTACHMENT = "delete_attachment";
	
	private static final String NEW_FROM = "new_from";
	private static final String NEW_SUBJECT = "new_subject";
	private static final String NEW_TO = "new_to";
	private static final String NEW_CC = "new_cc";
	private static final String NEW_BCC = "new_bcc";
	private static final String NEW_TEXT = "new_text";
	private static final String NEW_FILENAME = "new_filename";
	private static final String NEW_UPLOAD = "new_upload";
	private static final String NEW_BCC_TO_SELF = "new_bcc_to_self";
	
	private static final String LIST = "list";
	private static final String PREV = "prev";
	private static final String NEXT = "next";
	private static final String SORT_ID = "sort_id";
	private static final String SORT_SENDER = "sort_sender";
	private static final String SORT_SUBJECT = "sort_subject";
	private static final String SORT_DATE = "sort_date";
	private static final String SORT_SIZE = "sort_size";

	private static final String CONFIG_TEXT = "config_text";

	private static final boolean SHOW_HTML = true;
	private static final boolean TEXT_ONLY = false;
	
	/*
	 * name of configuration properties
	 */
	private static final String CONFIG_HOST = "host";

	private static final String CONFIG_PORTS_FIXED = "ports.fixed";
	private static final String CONFIG_PORTS_POP3 = "ports.pop3";
	private static final String CONFIG_PORTS_SMTP = "ports.smtp";

	private static final String CONFIG_SENDER_FIXED = "sender.fixed";
	private static final String CONFIG_SENDER_DOMAIN = "sender.domain";
	private static final String CONFIG_SENDER_NAME = "sender.name";
	
	private static final String CONFIG_COMPOSER_COLS = "composer.cols";
	private static final String CONFIG_COMPOSER_ROWS = "composer.rows";

	private static final String CONFIG_BCC_TO_SELF = "composer.bcc.to.self";
	static final String CONFIG_LEAVE_ON_SERVER = "pop3.leave.on.server";
	public static final String CONFIG_BACKGROUND_CHECK = "pop3.check.enable";
	public static final String CONFIG_CHECK_MINUTES = "pop3.check.interval.minutes";
	public static final String CONFIG_IDLE_SECONDS = "pop3.idle.timeout.seconds";
	private static final String CONFIG_DEBUG = "debug";

	private static final String RC_PROP_THEME = "routerconsole.theme";
	private static final String RC_PROP_UNIVERSAL_THEMING = "routerconsole.universal.theme";
	private static final String RC_PROP_FORCE_MOBILE_CONSOLE = "routerconsole.forceMobileConsole";
	private static final String CONFIG_THEME = "theme";
	private static final String DEFAULT_THEME = "light";

	private static final String spacer = "&nbsp;&nbsp;&nbsp;";
	private static final String thSpacer = "<th>&nbsp;</th>\n";
	
	static {
		Config.setPrefix( "susimail" );
		RELEASE = !Boolean.parseBoolean(Config.getProperty(CONFIG_DEBUG));
		Debug.setLevel( RELEASE ? Debug.ERROR : Debug.DEBUG );
	}

	/**
	 * sorts Mail objects by id field
	 * 
	 * @author susi
	 */
/****
	private static class IDSorter implements Comparator<String> {
		private final MailCache mailCache;
		
		public IDSorter( MailCache mailCache )
		{
			this.mailCache = mailCache;
		}
		
		public int compare(String arg0, String arg1) {
			Mail a = mailCache.getMail( arg0, MailCache.FETCH_HEADER );
			Mail b = mailCache.getMail( arg1, MailCache.FETCH_HEADER );
			if (a == null)
				return (b == null) ? 0 : 1;
			if (b == null)
				return -1;
			return a.id - b.id;
		}		
	}
****/

	/**
	 * Base for the various sorters
	 * 
	 * @since 0.9.13
	 */
	private abstract static class SorterBase implements Comparator<String>, Serializable {
		private final MailCache mailCache;
		
		/**
		 * Set MailCache object, where to get Mails from
		 * @param mailCache
		 */
		protected SorterBase( MailCache mailCache )
		{
			this.mailCache = mailCache;
		}
		
		/**
		 *  Gets mail from the cache, checks for null, then compares
		 */
		public int compare(String arg0, String arg1) {
			Mail a = mailCache.getMail( arg0, MailCache.FetchMode.CACHE_ONLY );
			Mail b = mailCache.getMail( arg1, MailCache.FetchMode.CACHE_ONLY );
			if (a == null)
				return (b == null) ? 0 : 1;
			if (b == null)
				return -1;
			int rv = compare(a, b);
			if (rv != 0)
				return rv;
			return fallbackCompare(a, b);
		}		

		/**
		 * @param a non-null
		 * @param b non-null
		 */
		protected abstract int compare(Mail a, Mail b);

		/**
		 * @param a non-null
		 * @param b non-null
		 */
		private int fallbackCompare(Mail a, Mail b) {
			return DateSorter.scompare(a, b);
		}
	}

	/**
	 * sorts Mail objects by sender field
	 * 
	 * @author susi
	 */
	private static class SenderSorter extends SorterBase {

		private final Comparator collator = Collator.getInstance();

		public SenderSorter( MailCache mailCache )
		{
			super(mailCache);
		}
		
		protected int compare(Mail a, Mail b) {
			String as = a.sender.replace("\"", "").replace("<", "").replace(">", "");
			String bs = b.sender.replace("\"", "").replace("<", "").replace(">", "");
			return collator.compare(as, bs);
		}		
	}

	/**
	 * sorts Mail objects by subject field
	 * @author susi
	 */
	private static class SubjectSorter extends SorterBase {
		private final Comparator collator = Collator.getInstance();

		public SubjectSorter( MailCache mailCache )
		{
			super(mailCache);
		}
		
		protected int compare(Mail a, Mail b) {
			String as = a.formattedSubject;
			String bs = b.formattedSubject;
			if (as.toLowerCase().startsWith("re:")) {
				as = as.substring(3).trim();
			} else if (as.toLowerCase().startsWith("fwd:")) {
				as = as.substring(4).trim();
			} else {
				String xre = _("Re:").toLowerCase();
				if (as.toLowerCase().startsWith(xre)) {
					as = as.substring(xre.length()).trim();
				} else {
					String xfwd = _("Fwd:").toLowerCase();
					if (as.toLowerCase().startsWith(xfwd))
						as = as.substring(xfwd.length()).trim();
				}
			}
			if (bs.toLowerCase().startsWith("re:")) {
				bs = bs.substring(3).trim();
			} else if (bs.toLowerCase().startsWith("fwd:")) {
				bs = bs.substring(4).trim();
			} else {
				String xre = _("Re:").toLowerCase();
				if (bs.toLowerCase().startsWith(xre)) {
					bs = bs.substring(xre.length()).trim();
				} else {
					String xfwd = _("Fwd:").toLowerCase();
					if (bs.toLowerCase().startsWith(xfwd))
						bs = bs.substring(xfwd.length()).trim();
				}
			}
			return collator.compare(as, bs);
		}		
	}

	/**
	 * sorts Mail objects by date field
	 * @author susi
	 */
	private static class DateSorter extends SorterBase {

		public DateSorter( MailCache mailCache )
		{
			super(mailCache);
		}
		
		protected int compare(Mail a, Mail b) {
			return scompare(a, b);
		}

		/**
		 * Use as fallback in other sorters
		 * @param a non-null
		 * @param b non-null
		 */
		public static int scompare(Mail a, Mail b) {
			return a.date != null ? ( b.date != null ? a.date.compareTo( b.date ) : -1 ) : ( b.date != null ? 1 : 0 );
		}		
	}

	/**
	 * sorts Mail objects by message size
	 * @author susi
	 */
	private static class SizeSorter extends SorterBase {

		public SizeSorter( MailCache mailCache )
		{
			super(mailCache);
		}
		
		protected int compare(Mail a, Mail b) {
			return a.getSize() - b.getSize();
		}		
	}
	
	/**
	 * data structure to hold any persistent data (to store them in session dictionary)
	 * @author susi
	 */
	private static class SessionObject implements HttpSessionBindingListener, NewMailListener {
		boolean pageChanged, markAll, clear, invert;
		int state, smtpPort;
		POP3MailBox mailbox;
		MailCache mailCache;
		Folder<String> folder;
		String user, pass, host, error, info;
		String replyTo, replyCC;
		String subject, body, showUIDL;
		public String sentMail;
		public ArrayList<Attachment> attachments;
		public boolean reallyDelete;
		String themePath, imgPath;
		boolean isMobile;
		boolean bccToSelf;
		
		SessionObject()
		{
			state = STATE_AUTH;
			bccToSelf = Boolean.parseBoolean(Config.getProperty( CONFIG_BCC_TO_SELF, "true" ));
		}

		/** @since 0.9.13 */
		public void valueBound(HttpSessionBindingEvent event) {}

		/**
		 * Close the POP3 socket if still open
		 * @since 0.9.13
		 */
		public void valueUnbound(HttpSessionBindingEvent event) {
			Debug.debug(Debug.DEBUG, "Session unbound: " + event.getSession().getId());
			POP3MailBox mbox = mailbox;
			if (mbox != null) {
				mbox.destroy();
				mailbox = null;
			}
		}

		/**
		 *  Relay from the checker to the webmail session object,
		 *  which relays to MailCache, which will fetch the mail from us
		 *  in a big circle
		 *
		 *  @since 0.9.13
		 */
		public void foundNewMail() {
			MailCache mc = mailCache;
			Folder<String> f = folder;
			if (mc != null && f != null) {
				String[] uidls = mc.getUIDLs();
				f.setElements(uidls);
			}
		}
	}

	/**
	 * returns html string of a form button with name and label
	 * 
	 * @param name
	 * @param label
	 * @return html string
	 */
	private static String button( String name, String label )
	{
		StringBuilder buf = new StringBuilder(128);
		buf.append("<input type=\"submit\" class=\"").append(name).append("\" name=\"")
		   .append(name).append("\" value=\"").append(label).append('"');
		if (name.equals(SEND) || name.equals(CANCEL) || name.equals(DELETE_ATTACHMENT) || name.equals(NEW_UPLOAD))
			buf.append(" onclick=\"cancelPopup()\"");
		buf.append('>');
		return buf.toString();
	}

	/**
	 * returns html string of a disabled form button with name and label
	 * 
	 * @param name
	 * @param label
	 * @return html string
	 */
	private static String button2( String name, String label )
	{
		return "<input type=\"submit\" name=\"" + name + "\" value=\"" + label + "\" disabled>";
	}

	/**
	 * returns a html string of the label and two imaged links using the parameter name
	 * (used for sorting buttons in folder view)
	 * 
	 * @param name
	 * @param label
	 * @return the string
	 */
	private static String sortHeader( String name, String label, String imgPath, String currentName, Folder.SortOrder currentOrder)
	{
		StringBuilder buf = new StringBuilder(128);
		buf.append(label).append("&nbsp;&nbsp;");
		if (name.equals(currentName) && currentOrder == Folder.SortOrder.UP) {
			buf.append("<img class=\"sort\" src=\"").append(imgPath).append("3up.png\" border=\"0\" alt=\"^\">\n");
		} else {
			buf.append("<a class=\"sort\" href=\"").append(myself).append('?').append(name).append("=up\">");
			buf.append("<img class=\"sort\" src=\"").append(imgPath).append("3up.png\" border=\"0\" alt=\"^\" style=\"opacity: 0.4;\">");
			buf.append("</a>\n");
		}
		if (name.equals(currentName) && currentOrder == Folder.SortOrder.DOWN) {
			buf.append("<img class=\"sort\" src=\"").append(imgPath).append("3down.png\" border=\"0\" alt=\"v\">");
		} else {
			buf.append("<a class=\"sort\" href=\"").append(myself).append('?').append(name).append("=down\">");
			buf.append("<img class=\"sort\" src=\"").append(imgPath).append("3down.png\" border=\"0\" alt=\"v\" style=\"opacity: 0.4;\">");
			buf.append("</a>");
		}
		return buf.toString();
	}

	/**
	 * check, if a given button "was pressed" in the received http request
	 * 
	 * @param request
	 * @param key
	 * @return true if pressed
	 */
	private static boolean buttonPressed( RequestWrapper request, String key )
	{
		String value = request.getParameter( key );
		return value != null && value.length() > 0;
	}
	/**
	 * recursively render all mail body parts
	 * 
	 * 1. if type is multipart/alternative, look for text/plain section and ignore others
	 * 2. if type is multipart/*, recursively call all these parts
	 * 3. if type is text/plain (or mail is not mime), print out
	 * 4. in all other cases print out message, that part is not displayed
	 * 
	 * @param out
	 * @param mailPart
	 * @param level is increased by recursively calling sub parts
	 */
	private static void showPart( PrintWriter out, MailPart mailPart, int level, boolean html )
	{
		String br = html ? "<br>\r\n" : "\r\n";
		
		if( html ) {
			out.println( "<!-- " );
			out.println( "Debug: Mail Part headers follow");
			for( int i = 0; i < mailPart.headerLines.length; i++ ) {
				// fix Content-Type: multipart/alternative; boundary="----------8CDE39ECAF2633"
				out.println( mailPart.headerLines[i].replace("--", "&mdash;") );
			}	
			out.println( "-->" );
		}
		
		if( mailPart.multipart ) {
			if( mailPart.type.equals("multipart/alternative")) {
				MailPart chosen = null;
				for( MailPart subPart : mailPart.parts ) {
					if( subPart.type != null && subPart.type.equals("text/plain"))
						chosen = subPart;
				}
				if( chosen != null ) {
					showPart( out, chosen, level + 1, html );
					return;
				}
			}
			for( MailPart part : mailPart.parts ) {
				showPart( out, part, level + 1, html );
			}
		}
		else if( mailPart.message ) {
			for( MailPart part : mailPart.parts ) {
				showPart( out, part, level + 1, html );
			}
		}
		else {
			boolean showBody = false;
			boolean prepareAttachment = false;
			String reason = "";
			StringBuilder body = null;
			
			String ident = quoteHTML(
					( mailPart.description != null ? mailPart.description + ", " : "" ) +
					( mailPart.filename != null ? mailPart.filename + ", " : "" ) +
					( mailPart.name != null ? mailPart.name + ", " : "" ) +
					( mailPart.type != null ? '(' + mailPart.type + ')' : _("unknown") ) );
			
			if( level == 0 && mailPart.version == null ) {
				/*
				 * not a MIME mail, so simply print it literally
				 */
				showBody = true;
			}
			if( showBody == false && mailPart.type != null ) {
				if( mailPart.type.equals("text/plain")) {
					showBody = true;
				}
				else
					prepareAttachment = true;
			}
			if( showBody ) {			
					String charset = mailPart.charset;
					if( charset == null ) {
						charset = "US-ASCII";
						// don't show this in text mode which is used to include the mail in the reply or forward
						if (html)
							reason += _("Warning: no charset found, fallback to US-ASCII.") + br;
					}
					try {
						ReadBuffer decoded = mailPart.decode(0);
						BufferedReader reader = new BufferedReader( new InputStreamReader( new ByteArrayInputStream( decoded.content, decoded.offset, decoded.length ), charset ) );
						body = new StringBuilder();
						String line;
						while( ( line = reader.readLine() ) != null ) {
							body.append( quoteHTML( line ) );
							body.append( br );
						}
					}
					catch( UnsupportedEncodingException uee ) {
						showBody = false;
						reason = _("Charset \\''{0}\\'' not supported.", quoteHTML( mailPart.charset )) + br;
					}
					catch (Exception e1) {
						showBody = false;
						reason += _("Part ({0}) not shown, because of {1}", ident, e1.toString()) + br;
					}
			}
			if( html )
				out.println( "<tr class=\"mailbody\"><td colspan=\"2\" align=\"center\">" );
			if( reason != null && reason.length() > 0 ) {
				if( html )
					out.println( "<p class=\"info\">");
				out.println( reason );
				if( html )
					out.println( "</p>" );
			}
			if( showBody ) {
				if( html )
					out.println( "<p class=\"mailbody\">" );
				out.println( body.toString() );
				if( html )
					out.println( "</p>" );
			}
			if( prepareAttachment ) {
				if( html ) {
					out.println( "<hr><p class=\"mailbody\">" );
					String type = mailPart.type;
					if (type != null && type.startsWith("image/")) {
						// we at least show images safely...
						out.println("<img src=\"" + myself + "?" + RAW_ATTACHMENT + "=" +
							 mailPart.hashCode() + "\">");
					} else if (type != null && (
						// type list from snark
						type.startsWith("audio/") || type.equals("application/ogg") ||
					        type.startsWith("video/") ||
						type.equals("application/zip") || type.equals("application/x-gtar") ||
						type.equals("application/compress") || type.equals("application/gzip") ||
						type.equals("application/x-7z-compressed") || type.equals("application/x-rar-compressed") ||
						type.equals("application/x-tar") || type.equals("application/x-bzip2"))) {
						out.println( "<a href=\"" + myself + "?" + RAW_ATTACHMENT + "=" +
							 mailPart.hashCode() + "\">" + _("Download attachment {0}", ident) + "</a>");
					} else {
						out.println( "<a target=\"_blank\" href=\"" + myself + "?" + DOWNLOAD + "=" +
							 mailPart.hashCode() + "\">" + _("Download attachment {0}", ident) + "</a>" +
							 " (" + _("File is packed into a zipfile for security reasons.") + ')');
					}
					out.println( "</p>" );					
				}
				else {
					out.println( _("Attachment ({0}).", ident) );
				}
			}
			if( html )
				out.println( "</td></tr>" );
		}
	}
	/**
	 * prepare line for presentation between html tags
	 * 
	 * - quote html tags
	 * 
	 * @param line
	 * @return escaped string
	 */
	static String quoteHTML( String line )
	{
		if( line != null )
			line = DataHelper.escapeHTML(line);
		else
			line = "";
		return line;
	}
	/**
	 * 
	 * @param sessionObject
	 * @param request
	 */
	private static void processLogin( SessionObject sessionObject, RequestWrapper request )
	{
		if( sessionObject.state == STATE_AUTH ) {
			String user = request.getParameter( USER );
			String pass = request.getParameter( PASS );
			String host = request.getParameter( HOST );
			String pop3Port = request.getParameter( POP3 );
			String smtpPort = request.getParameter( SMTP );
			boolean fixedPorts = Boolean.parseBoolean(Config.getProperty( CONFIG_PORTS_FIXED, "true" ));
			if (fixedPorts) {
				host = Config.getProperty( CONFIG_HOST, DEFAULT_HOST );
				pop3Port = Config.getProperty( CONFIG_PORTS_POP3, "" + DEFAULT_POP3PORT );
				smtpPort = Config.getProperty( CONFIG_PORTS_SMTP, "" + DEFAULT_SMTPPORT );
			}
			boolean doContinue = true;

			/*
			 * security :(
			 */
			boolean offline = buttonPressed(request, OFFLINE);
			if (buttonPressed(request, LOGIN) || offline) {
				
				if( user == null || user.length() == 0 ) {
					sessionObject.error += _("Need username for authentication.") + '\n';
					doContinue = false;
				}
				if( pass == null || pass.length() == 0 ) {
					sessionObject.error += _("Need password for authentication.") + '\n';
					doContinue = false;
				}
				if( host == null || host.length() == 0 ) {
					sessionObject.error += _("Need hostname for connect.") + '\n';
					doContinue = false;
				}
				int pop3PortNo = 0;
				if( pop3Port == null || pop3Port.length() == 0 ) {
					sessionObject.error += _("Need port number for pop3 connect.") + '\n';
					doContinue = false;
				}
				else {
					try {
						pop3PortNo = Integer.parseInt( pop3Port );
						if( pop3PortNo < 0 || pop3PortNo > 65535 ) {
							sessionObject.error += _("POP3 port number is not in range 0..65535.") + '\n';
							doContinue = false;
						}
					}
					catch( NumberFormatException nfe )
					{
						sessionObject.error += _("POP3 port number is invalid.") + '\n';
						doContinue = false;
					}
				}
				int smtpPortNo = 0;
				if( smtpPort == null || smtpPort.length() == 0 ) {
					sessionObject.error += _("Need port number for smtp connect.") + '\n';
					doContinue = false;
				}
				else {
					try {
						smtpPortNo = Integer.parseInt( smtpPort );
						if( smtpPortNo < 0 || smtpPortNo > 65535 ) {
							sessionObject.error += _("SMTP port number is not in range 0..65535.") + '\n';
							doContinue = false;
						}
					}
					catch( NumberFormatException nfe )
					{
						sessionObject.error += _("SMTP port number is invalid.") + '\n';
						doContinue = false;
					}
				}
				if( doContinue ) {
					POP3MailBox mailbox = new POP3MailBox( host, pop3PortNo, user, pass );
					if (offline || mailbox.connectToServer()) {
						sessionObject.mailbox = mailbox;
						sessionObject.user = user;
						sessionObject.pass = pass;
						sessionObject.host = host;
						sessionObject.smtpPort = smtpPortNo;
						sessionObject.state = STATE_LIST;
						MailCache mc = new MailCache(mailbox, host, pop3PortNo, user, pass);
						sessionObject.mailCache = mc;
						sessionObject.folder = new Folder<String>();
						if (!offline) {
							// prime the cache, request all headers at once
							// otherwise they are pulled one at a time by sortBy() below
							mc.getMail(MailCache.FetchMode.HEADER);
						}
						// get through cache so we have the disk-only ones too
						String[] uidls = mc.getUIDLs();
						sessionObject.folder.setElements(uidls);
						
						//sessionObject.folder.addSorter( SORT_ID, new IDSorter( sessionObject.mailCache ) );
						sessionObject.folder.addSorter( SORT_SENDER, new SenderSorter( sessionObject.mailCache ) );
						sessionObject.folder.addSorter( SORT_SUBJECT, new SubjectSorter( sessionObject.mailCache ) );
						sessionObject.folder.addSorter( SORT_DATE, new DateSorter( sessionObject.mailCache ) );
						sessionObject.folder.addSorter( SORT_SIZE, new SizeSorter( sessionObject.mailCache ) );
						// reverse sort, latest mail first
						sessionObject.folder.setSortingDirection(Folder.SortOrder.UP);
						sessionObject.folder.sortBy(SORT_DATE);
						sessionObject.reallyDelete = false;
						if (offline)
							Debug.debug(Debug.DEBUG, "OFFLINE MODE");
						else
							Debug.debug(Debug.DEBUG, "CONNECTED, YAY");
						// we do this after the initial priming above
						mailbox.setNewMailListener(sessionObject);
					} else {
						sessionObject.error += mailbox.lastError();
						Debug.debug(Debug.DEBUG, "LOGIN FAIL, REMOVING SESSION");
						HttpSession session = request.getSession();
						session.removeAttribute( "sessionObject" );
						session.invalidate();
						mailbox.destroy();
						sessionObject.mailbox = null;
						sessionObject.mailCache = null;
						Debug.debug(Debug.DEBUG, "NOT CONNECTED, BOO");
					}
				}
			}
		}
	}

	/**
	 * 
	 * @param sessionObject
	 * @param request
	 */
	private static void processLogout( SessionObject sessionObject, RequestWrapper request, boolean isPOST )
	{
		if( buttonPressed( request, LOGOUT ) && isPOST) {
			Debug.debug(Debug.DEBUG, "LOGOUT, REMOVING SESSION");
			HttpSession session = request.getSession();
			session.removeAttribute( "sessionObject" );
			session.invalidate();
			POP3MailBox mailbox = sessionObject.mailbox;
			if (mailbox != null) {
				mailbox.destroy();
				sessionObject.mailbox = null;
				sessionObject.mailCache = null;
			}
			sessionObject.info += _("User logged out.") + '\n';
			sessionObject.state = STATE_AUTH;
		} else if( sessionObject.mailbox == null ) {
			sessionObject.error += _("Internal error, lost connection.") + '\n';
			sessionObject.state = STATE_AUTH;
		}
	}

	/**
	 * Process all buttons, which possibly change internal state.
	 * Also processes ?show=x for a GET
	 * 
	 * @param sessionObject
	 * @param request
	 * @param isPOST disallow button pushes if false
	 */
	private static void processStateChangeButtons(SessionObject sessionObject, RequestWrapper request, boolean isPOST )
	{
		/*
		 * LOGIN/LOGOUT
		 */
		if( sessionObject.state == STATE_AUTH && isPOST )
			processLogin( sessionObject, request );

		if( sessionObject.state != STATE_AUTH && sessionObject.state != STATE_CONFIG )
			processLogout( sessionObject, request, isPOST );

		/*
		 *  compose dialog
		 */
		if( sessionObject.state == STATE_NEW && isPOST ) {
			// We have to make sure to get the state right even if
			// the user hit the back button previously
			if( buttonPressed( request, SEND ) ) {
				if( sendMail( sessionObject, request ) )
					sessionObject.state = STATE_LIST;
			} else if (buttonPressed( request, CANCEL ) ||
			    buttonPressed( request, SHOW )  ||       // A param, not a button, but we could be lost
			    buttonPressed( request, PREVPAGE ) ||    // All these buttons are not shown but we could be lost
			    buttonPressed( request, NEXTPAGE ) ||
			    buttonPressed( request, FIRSTPAGE ) ||
			    buttonPressed( request, LASTPAGE ) ||
			    buttonPressed( request, SETPAGESIZE ) ||
			    buttonPressed( request, MARKALL ) ||
			    buttonPressed( request, CLEAR ) ||
			    buttonPressed( request, INVERT ) ||
			    buttonPressed( request, SORT_ID ) ||
			    buttonPressed( request, SORT_SENDER ) ||
			    buttonPressed( request, SORT_SUBJECT ) ||
			    buttonPressed( request, SORT_DATE ) ||
			    buttonPressed( request, SORT_SIZE ) ||
			    buttonPressed( request, REFRESH ) ||
			    buttonPressed( request, LIST )) {
				sessionObject.state = STATE_LIST;
				sessionObject.sentMail = null;	
				if( sessionObject.attachments != null )
					sessionObject.attachments.clear();
			} else if (buttonPressed( request, PREV ) ||     // All these buttons are not shown but we could be lost
			    buttonPressed( request, NEXT )  ||
			    buttonPressed( request, DELETE )) {
				sessionObject.state = STATE_SHOW;
				sessionObject.sentMail = null;	
				if( sessionObject.attachments != null )
					sessionObject.attachments.clear();
			}
		}
		/*
		 * message dialog or config
		 */
		if((sessionObject.state == STATE_SHOW || sessionObject.state == STATE_CONFIG) && isPOST ) {
			if( buttonPressed( request, LIST ) ) { 
				sessionObject.state = STATE_LIST;
			} else if (buttonPressed( request, CANCEL ) ||
			    buttonPressed( request, PREVPAGE ) ||    // All these buttons are not shown but we could be lost
			    buttonPressed( request, NEXTPAGE ) ||
			    buttonPressed( request, FIRSTPAGE ) ||
			    buttonPressed( request, LASTPAGE ) ||
			    buttonPressed( request, SETPAGESIZE ) ||
			    buttonPressed( request, MARKALL ) ||
			    buttonPressed( request, CLEAR ) ||
			    buttonPressed( request, INVERT ) ||
			    buttonPressed( request, SORT_ID ) ||
			    buttonPressed( request, SORT_SENDER ) ||
			    buttonPressed( request, SORT_SUBJECT ) ||
			    buttonPressed( request, SORT_DATE ) ||
			    buttonPressed( request, SORT_SIZE ) ||
			    buttonPressed( request, REFRESH )) {
				sessionObject.state = STATE_LIST;
			}
		}

		/*
		 * config
		 */
		if (sessionObject.state == STATE_CONFIG && isPOST) {
			if (buttonPressed(request, OFFLINE)) {       // lost
				sessionObject.state = STATE_AUTH;
			} else if (buttonPressed(request, LOGIN)) {  // lost
				sessionObject.state = STATE_AUTH;
			}
		}

		/*
		 * buttons on both folder and message dialog
		 */
		if( sessionObject.state == STATE_SHOW || sessionObject.state == STATE_LIST ) {
			if( isPOST && buttonPressed( request, NEW ) ) {
				sessionObject.state = STATE_NEW;
			}
			
			boolean reply = false;
			boolean replyAll = false;
			boolean forward = false;
			sessionObject.replyTo = null;
			sessionObject.replyCC = null;
			sessionObject.body = null;
			sessionObject.subject = null;
			
			if( buttonPressed( request, REPLY ) )
				reply = true;
			
			if( buttonPressed( request, REPLYALL ) ) {
				replyAll = true;
			}
			if( buttonPressed( request, FORWARD ) ) {
				forward = true;
			}
			if( reply || replyAll || forward ) {
				/*
				 * try to find message
				 */
				String uidl = null;
				if( sessionObject.state == STATE_LIST ) {
					// these buttons are now hidden on the folder page,
					// but the idea is to use the first checked message
					List<Integer> items = getCheckedItems(request);
					if (!items.isEmpty()) {
						int pos = items.get(0).intValue();
						uidl = sessionObject.folder.getElementAtPosXonCurrentPage( pos );
					}
				}
				else {
					uidl = sessionObject.showUIDL;
				}
				
				if( uidl != null ) {
					Mail mail = sessionObject.mailCache.getMail( uidl, MailCache.FetchMode.ALL );
					/*
					 * extract original sender from Reply-To: or From:
					 */
					MailPart part = mail != null ? mail.getPart() : null;
					if (part != null) {
						if( reply || replyAll ) {
							if( mail.reply != null && Mail.validateAddress( mail.reply ) )
								sessionObject.replyTo = mail.reply;
							else if( mail.sender != null && Mail.validateAddress( mail.sender ) )
								sessionObject.replyTo = mail.sender;
							sessionObject.subject = _("Re:") + ' ' + mail.formattedSubject;
							StringWriter text = new StringWriter();
							PrintWriter pw = new PrintWriter( text );
							pw.println( _("On {0} {1} wrote:", mail.formattedDate + " UTC", sessionObject.replyTo) );
							StringWriter text2 = new StringWriter();
							PrintWriter pw2 = new PrintWriter( text2 );
							showPart( pw2, part, 0, TEXT_ONLY );
							pw2.flush();
							String[] lines = text2.toString().split( "\r\n" );
							for( int i = 0; i < lines.length; i++ )
								pw.println( "> " + lines[i] );
							pw.flush();
							sessionObject.body = text.toString();
						}
						if( replyAll ) {
							/*
							 * extract additional recipients
							 */
							StringBuilder buf = new StringBuilder();
							String pad = "";
							if( mail.to != null ) {
								for( int i = 0; i < mail.to.length; i++ ) {
									buf.append( pad );
									buf.append(mail.to[i]);
									pad = ", ";
								}
							}
							if( mail.cc != null ) {
								for( int i = 0; i < mail.cc.length; i++ ) {
									buf.append( pad );
									buf.append(mail.cc[i]);
									pad = ", ";
								}
							}
							if( buf.length() > 0 )
								sessionObject.replyCC = buf.toString();
						}
						if( forward ) {
							sessionObject.subject = _("Fwd:") + ' ' + mail.formattedSubject;
							String sender = null;
							if( mail.reply != null && Mail.validateAddress( mail.reply ) )
								sender = Mail.getAddress( mail.reply );
							else if( mail.sender != null && Mail.validateAddress( mail.sender ) )
								sender = Mail.getAddress( mail.sender );
							
							StringWriter text = new StringWriter();
							PrintWriter pw = new PrintWriter( text );
							pw.println();
							pw.println();
							pw.println();
							pw.println( "---- " + _("begin forwarded mail") + " ----" );
							pw.println( "From: " + sender );
							if( mail.to != null ) {
								String pad = "To: ";
								for( int i = 0; i < mail.to.length; i++ ) {
									pw.println( pad );
									pw.println(mail.to[i]);
									pad = "    ";
								}
							}
							if( mail.cc != null ) {
								String pad = "Cc: ";
								for( int i = 0; i < mail.cc.length; i++ ) {
									pw.println( pad );
									pw.println(mail.cc[i]);
									pad = "    ";
								}
							}
							if( mail.dateString != null )
								pw.print( "Date: " + mail.dateString );
							pw.println();
							showPart( pw, part, 0, TEXT_ONLY );
							pw.println( "----  " + _("end forwarded mail") + "  ----" );
							pw.flush();
							sessionObject.body = text.toString();
						}
						sessionObject.state = STATE_NEW;
					}
					else {
						sessionObject.error += _("Could not fetch mail body.") + '\n';
					}
				}
			}
		}

		/*
		 * folder view
		 * SHOW is the one parameter that's a link, not a button, so we allow it for GET
		 */
		if( sessionObject.state == STATE_LIST || sessionObject.state == STATE_SHOW) {
			/*
			 * check if user wants to view a message
			 */
			String show = request.getParameter( SHOW );
			if( show != null && show.length() > 0 ) {
				try {

					int id = Integer.parseInt( show );
					
					if( id >= 0 && id < sessionObject.folder.getPageSize() ) {
						String uidl = sessionObject.folder.getElementAtPosXonCurrentPage( id );
						if( uidl != null ) {
							sessionObject.state = STATE_SHOW;
							sessionObject.showUIDL = uidl;
						}
					}
				}
				catch( NumberFormatException nfe )
				{
					sessionObject.error += _("Message id not valid.") + '\n';
				}
			}
		}
	}

	/**
	 * Returns e.g. 3,5 for ?check3=1&check5=1 (or POST equivalent)
	 * @param request
	 * @return non-null
	 */
	private static List<Integer> getCheckedItems(RequestWrapper request) {
		List<Integer> rv = new ArrayList<Integer>(8);
		for( Enumeration<String> e = request.getParameterNames(); e.hasMoreElements(); ) {
			String parameter = e.nextElement();
			if( parameter.startsWith( "check" ) && request.getParameter( parameter ).equals("1")) {
				String number = parameter.substring( 5 );
				try {
					rv.add(Integer.valueOf(number));
				} catch( NumberFormatException nfe ) {}
			}
		}
		return rv;
	}

	/**
	 * @param sessionObject
	 * @param request
	 */
	private static void processGenericButtons(SessionObject sessionObject, RequestWrapper request)
	{
		// these two buttons are only on the folder view now
		if( buttonPressed( request, RELOAD ) ) {
			Config.reloadConfiguration();
			int oldPageSize = sessionObject.folder.getPageSize();
			int pageSize = Config.getProperty( Folder.PAGESIZE, Folder.DEFAULT_PAGESIZE );
			if( pageSize != oldPageSize )
				sessionObject.folder.setPageSize( pageSize );
			sessionObject.info = _("Configuration reloaded");
		}
		if( buttonPressed( request, REFRESH ) ) {
			// TODO how to do a "No new mail" message?
			sessionObject.mailbox.refresh();
			sessionObject.error += sessionObject.mailbox.lastError();
			sessionObject.mailCache.getMail(MailCache.FetchMode.HEADER);
			// get through cache so we have the disk-only ones too
			String[] uidls = sessionObject.mailCache.getUIDLs();
			if (uidls != null)
				sessionObject.folder.setElements(uidls);
			sessionObject.pageChanged = true;
		}
	}

	/**
	 * process buttons of compose message dialog
	 * This must be called BEFORE processStateChangeButtons so we can add the attachment before SEND
	 *
	 * @param sessionObject
	 * @param request
	 */
	private static void processComposeButtons(SessionObject sessionObject, RequestWrapper request)
	{
		String filename = request.getFilename( NEW_FILENAME );
		// We handle an attachment whether sending or uploading
		if (filename != null &&
		    (buttonPressed(request, NEW_UPLOAD) || buttonPressed(request, SEND))) {
			Debug.debug(Debug.DEBUG, "Got filename in compose form: " + filename);
			int i = filename.lastIndexOf( "/" );
			if( i != - 1 )
				filename = filename.substring( i + 1 );
			i = filename.lastIndexOf( "\\" );
			if( i != -1 )
				filename = filename.substring( i + 1 );
			if( filename != null && filename.length() > 0 ) {
				InputStream in = request.getInputStream( NEW_FILENAME );
				int l;
				try {
					l = in.available();
					if( l > 0 ) {
						byte buf[] = new byte[l];
						in.read( buf );
						String contentType = request.getContentType( NEW_FILENAME );
						Encoding encoding;
						String encodeTo;
						if( contentType.toLowerCase(Locale.US).startsWith( "text/" ) )
							encodeTo = "quoted-printable";
						else
							encodeTo = "base64";
						encoding = EncodingFactory.getEncoding( encodeTo );
						try {
							if( encoding != null ) {
								String data = encoding.encode(buf);
								if( sessionObject.attachments == null )
									sessionObject.attachments = new ArrayList<Attachment>();
								sessionObject.attachments.add(
									new Attachment(filename, contentType, encodeTo, data)
								);
							}
							else {
								sessionObject.error += _("No Encoding found for {0}", encodeTo) + '\n';
							}
						}
						catch (EncodingException e1) {
							sessionObject.error += _("Could not encode data: {0}", e1.getMessage());
						}
					}
				}
				catch (IOException e) {
					sessionObject.error += _("Error reading uploaded file: {0}", e.getMessage()) + '\n';
				}
			}
		}
		else if( sessionObject.attachments != null && buttonPressed( request, DELETE_ATTACHMENT ) ) {
			for (Integer item : getCheckedItems(request)) {
				int n = item.intValue();
				for( int i = 0; i < sessionObject.attachments.size(); i++ ) {
					Attachment attachment = sessionObject.attachments.get(i);
					if( attachment.hashCode() == n ) {
						sessionObject.attachments.remove( i );
						break;
					}
				}
			}			
		}
	}

	/**
	 * process buttons of message view
	 * @param sessionObject
	 * @param request
	 */
	private static void processMessageButtons(SessionObject sessionObject, RequestWrapper request)
	{
		if( buttonPressed( request, PREV ) ) {
			String uidl = sessionObject.folder.getPreviousElement( sessionObject.showUIDL );
			if( uidl != null )
				sessionObject.showUIDL = uidl;
		}
		if( buttonPressed( request, NEXT ) ) {
			String uidl = sessionObject.folder.getNextElement( sessionObject.showUIDL );
			if( uidl != null )
				sessionObject.showUIDL = uidl;
		}
		
		sessionObject.reallyDelete = buttonPressed( request, DELETE );
		
		if( buttonPressed( request, REALLYDELETE ) ) {
			/*
			 * first find the next message
			 */
			String nextUIDL = sessionObject.folder.getNextElement( sessionObject.showUIDL );
			if( nextUIDL == null ) {
				/*
				 * nothing found? then look for the previous one
				 */
				nextUIDL = sessionObject.folder.getPreviousElement( sessionObject.showUIDL );
				if( nextUIDL == null )
					/*
					 * still nothing found? then this was the last message, so go back to the folder
					 */
					sessionObject.state = STATE_LIST;
			}
			sessionObject.mailCache.delete( sessionObject.showUIDL );
			sessionObject.folder.removeElement(sessionObject.showUIDL);
			sessionObject.showUIDL = nextUIDL;
		}
	}		

	/**
	 * process download link in message view
	 * @param sessionObject
	 * @param request
	 * @return If true, we sent an attachment or 404, do not send any other response
	 */
	private static boolean processDownloadLink(SessionObject sessionObject, RequestWrapper request, HttpServletResponse response)
	{
		String str = request.getParameter(DOWNLOAD);
		boolean isRaw = false;
		if (str == null) {
			str = request.getParameter(RAW_ATTACHMENT);
			isRaw = str != null;
		}       	
		if( str != null ) {
			try {
				int hashCode = Integer.parseInt( str );
				Mail mail = sessionObject.mailCache.getMail( sessionObject.showUIDL, MailCache.FetchMode.ALL );
				MailPart part = mail != null ? getMailPartFromHashCode( mail.getPart(), hashCode ) : null;
				if( part != null ) {
					if (sendAttachment(sessionObject, part, response, isRaw))
						return true;
				}
			} catch( NumberFormatException nfe ) {}
			// error if we get here
			sessionObject.error += _("Attachment not found.");
			if (isRaw) {
				try {
					response.sendError(404, _("Attachment not found."));
				} catch (IOException ioe) {}
			}
		}
		return isRaw;
	}


	/**
	 * Process save-as link in message view
	 *
	 * @param sessionObject
	 * @param request
	 * @return If true, we sent the file or 404, do not send any other response
	 * @since 0.9.18
	 */
	private static boolean processSaveAsLink(SessionObject sessionObject, RequestWrapper request, HttpServletResponse response)
	{
		String str = request.getParameter(SAVE_AS);
		if( str == null )
			return false;
		Mail mail = sessionObject.mailCache.getMail( sessionObject.showUIDL, MailCache.FetchMode.ALL );
		if( mail != null ) {
			if (sendMailSaveAs(sessionObject, mail, response))
				return true;
		}
		// error if we get here
		sessionObject.error += _("Message not found.");
		try {
			response.sendError(404, _("Message not found."));
		} catch (IOException ioe) {}
		return true;
	}

	/**
	 * @param hashCode
	 * @return the part or null
	 */
	private static MailPart getMailPartFromHashCode( MailPart part, int hashCode )
	{
		if( part == null )
			return null;
		
		if( part.hashCode() == hashCode )
			return part;
		
		if( part.multipart || part.message ) {
			for( MailPart p : part.parts ) {
				MailPart subPart = getMailPartFromHashCode( p, hashCode );
				if( subPart != null )
					return subPart;
			}
		}
		return null;
	}

	/**
	 * process buttons of folder view
	 * @param sessionObject
	 * @param request
	 */
	private static void processFolderButtons(SessionObject sessionObject, RequestWrapper request)
	{
		/*
		 * process paging buttons
		 */
		if (buttonPressed(request, SETPAGESIZE)) {
			try {
				int pageSize = Math.max(5, Integer.parseInt(request.getParameter(PAGESIZE)));
				int oldPageSize = sessionObject.folder.getPageSize();
				if( pageSize != oldPageSize )
					sessionObject.folder.setPageSize( pageSize );
			}
			catch( NumberFormatException nfe ) {
				sessionObject.error += _("Invalid pagesize number, resetting to default value.") + '\n';
			}
		}
		if( buttonPressed( request, PREVPAGE ) ) {
			sessionObject.pageChanged = true;
			sessionObject.folder.previousPage();
		}
		else if( buttonPressed( request, NEXTPAGE ) ) {
			sessionObject.pageChanged = true;
			sessionObject.folder.nextPage();
		}
		else if( buttonPressed( request, FIRSTPAGE ) ) {
			sessionObject.pageChanged = true;
			sessionObject.folder.firstPage();
		}
		else if( buttonPressed( request, LASTPAGE ) ) {
			sessionObject.pageChanged = true;
			sessionObject.folder.lastPage();
		}
		else if( buttonPressed( request, DELETE ) ) {
			int m = getCheckedItems(request).size();
			if (m > 0)
				sessionObject.reallyDelete = true;
			else
				sessionObject.error += _("No messages marked for deletion.") + '\n';
		}
		else {
			if( buttonPressed( request, REALLYDELETE ) ) {
				List<String> toDelete = new ArrayList<String>();
				for (Integer item : getCheckedItems(request)) {
					int n = item.intValue();
					String uidl = sessionObject.folder.getElementAtPosXonCurrentPage( n );
					if( uidl != null )
						toDelete.add(uidl);
				}
				int numberDeleted = toDelete.size();
				if (numberDeleted > 0) {
					sessionObject.mailCache.delete(toDelete);
					sessionObject.folder.removeElements(toDelete);
					sessionObject.pageChanged = true;
					sessionObject.info += ngettext("1 message deleted.", "{0} messages deleted.", numberDeleted);
					//sessionObject.error += _("Error deleting message: {0}", sessionObject.mailbox.lastError()) + '\n';
				}
			}
			sessionObject.reallyDelete = false;
		}
		
		sessionObject.markAll = buttonPressed( request, MARKALL );
		sessionObject.clear = buttonPressed( request, CLEAR );
		sessionObject.invert = buttonPressed( request, INVERT );
	}

	/*
	 * process sorting buttons
	 */
	private static void processSortingButtons(SessionObject sessionObject, RequestWrapper request)
	{
		//processSortingButton( sessionObject, request, SORT_ID );
		processSortingButton( sessionObject, request, SORT_SENDER );
		processSortingButton( sessionObject, request, SORT_SUBJECT );
		processSortingButton( sessionObject, request, SORT_DATE );
		processSortingButton( sessionObject, request, SORT_SIZE );		
	}

	/**
	 * @param sessionObject
	 * @param request
	 * @param sort_id
	 */
	private static void processSortingButton(SessionObject sessionObject, RequestWrapper request, String sort_id )
	{
		String str = request.getParameter( sort_id );
		if( str != null ) {
			if( str.equalsIgnoreCase("up")) {
				sessionObject.folder.setSortingDirection(Folder.SortOrder.UP);
				sessionObject.folder.sortBy( sort_id );
			} else 	if( str.equalsIgnoreCase("down")) {
				sessionObject.folder.setSortingDirection(Folder.SortOrder.DOWN);
				sessionObject.folder.sortBy( sort_id );
			}
		}
	}

	/*
	 * process config buttons, both entering and exiting
	 */
	private static void processConfigButtons(SessionObject sessionObject, RequestWrapper request) {
		if (buttonPressed(request, SAVE)) {
			try {
				String raw = request.getParameter(CONFIG_TEXT);
				if (raw == null)
					return;
				Properties props = new Properties();
				DataHelper.loadProps(props, new ByteArrayInputStream(DataHelper.getUTF8(raw)));
				Config.saveConfiguration(props);
				String ps = props.getProperty(Folder.PAGESIZE);
				if (sessionObject.folder != null && ps != null) {
					try {
						int pageSize = Math.max(5, Integer.parseInt(request.getParameter(PAGESIZE)));
						int oldPageSize = sessionObject.folder.getPageSize();
						if( pageSize != oldPageSize )
							sessionObject.folder.setPageSize( pageSize );
					} catch( NumberFormatException nfe ) {}
				}
				boolean release = !Boolean.parseBoolean(props.getProperty(CONFIG_DEBUG));
				Debug.setLevel( release ? Debug.ERROR : Debug.DEBUG );
				sessionObject.state = sessionObject.folder != null ? STATE_LIST : STATE_AUTH;
				sessionObject.info = _("Configuration saved");
			} catch (IOException ioe) {
				sessionObject.error = ioe.toString();
			}
		} else if (buttonPressed(request, SETPAGESIZE)) {
			try {
				int pageSize = Math.max(5, Integer.parseInt(request.getParameter(PAGESIZE)));
				Properties props = Config.getProperties();
				props.setProperty(Folder.PAGESIZE, String.valueOf(pageSize));
				Config.saveConfiguration(props);
				if (sessionObject.folder != null) {
					int oldPageSize = sessionObject.folder.getPageSize();
					if( pageSize != oldPageSize )
						sessionObject.folder.setPageSize( pageSize );
					sessionObject.state = STATE_LIST;
				} else {
					sessionObject.state = STATE_AUTH;
				}
			} catch (IOException ioe) {
				sessionObject.error = ioe.toString();
			} catch( NumberFormatException nfe ) {
				sessionObject.error += _("Invalid pagesize number, resetting to default value.") + '\n';
			}
		} else if (buttonPressed(request, CANCEL)) {
			sessionObject.state = (sessionObject.folder != null) ? STATE_LIST : STATE_AUTH;
		} else if (buttonPressed(request, CONFIGURE)) {
			sessionObject.state = STATE_CONFIG;
		}
	}

	/**
	 * @param httpSession
	 * @return non-null
	 */
	private synchronized SessionObject getSessionObject( HttpSession httpSession )
	{
		SessionObject sessionObject = (SessionObject)httpSession.getAttribute( "sessionObject" );

		if( sessionObject == null ) {
			sessionObject = new SessionObject();
			httpSession.setAttribute( "sessionObject", sessionObject );
			Debug.debug(Debug.DEBUG, "NEW session " + httpSession.getId() + " state = " + sessionObject.state);
		} else {
			Debug.debug(Debug.DEBUG, "Existing session " + httpSession.getId() + " state = " + sessionObject.state +
				" created " + new Date(httpSession.getCreationTime()));
		}
		return sessionObject;
	}

    /**
     * Copied from net.i2p.router.web.CSSHelper
     * @param ua null ok
     * @since 0.9.7
     */
    private static boolean isMobile(String ua) {
        if (ua == null)
            return false;
        return
                               // text
                              (ua.startsWith("Lynx") || ua.startsWith("w3m") ||
                               ua.startsWith("ELinks") || ua.startsWith("Links") ||
                               ua.startsWith("Dillo") || ua.startsWith("Emacs-w3m") ||
                               // mobile
                               // http://www.zytrax.com/tech/web/mobile_ids.html
                               // Android tablet UAs don't have "Mobile" in them
                               (ua.contains("Android") && ua.contains("Mobile")) ||
                               ua.contains("iPhone") ||
                               ua.contains("iPod") || ua.contains("iPad") ||
                               ua.contains("Kindle") || ua.contains("Mobile") ||
                               ua.contains("Nintendo Wii") ||
                               ua.contains("Opera Mini") || ua.contains("Opera Mobi") ||
                               ua.contains("Palm") ||
                               ua.contains("PLAYSTATION") || ua.contains("Playstation") ||
                               ua.contains("Profile/MIDP-") || ua.contains("SymbianOS") ||
                               ua.contains("Windows CE") || ua.contains("Windows Phone") ||
                               ua.startsWith("BlackBerry") || ua.startsWith("DoCoMo") ||
                               ua.startsWith("Nokia") || ua.startsWith("OPWV-SDK") ||
                               ua.startsWith("MOT-") || ua.startsWith("SAMSUNG-") ||
                               ua.startsWith("nook") || ua.startsWith("SCH-") ||
                               ua.startsWith("SEC-") || ua.startsWith("SonyEricsson") ||
                               ua.startsWith("Vodafone"));
    }
	/**
	 * The entry point for all web page loads
	 * 
	 * @param httpRequest
	 * @param response
	 * @param isPOST disallow button pushes if false
	 * @throws IOException
	 * @throws ServletException
	 */
	private void processRequest( HttpServletRequest httpRequest, HttpServletResponse response, boolean isPOST )
	throws IOException, ServletException
	{
		String theme = Config.getProperty(CONFIG_THEME, DEFAULT_THEME);
		I2PAppContext ctx = I2PAppContext.getGlobalContext();
		boolean universalTheming = ctx.getBooleanProperty(RC_PROP_UNIVERSAL_THEMING);
		if (universalTheming) {
			// Fetch routerconsole theme (or use our default if it doesn't exist)
			theme = ctx.getProperty(RC_PROP_THEME, DEFAULT_THEME);
			// Ensure that theme exists
			String[] themes = getThemes();
			boolean themeExists = false;
			for (int i = 0; i < themes.length; i++) {
				if (themes[i].equals(theme))
					themeExists = true;
			}
			if (!themeExists) {
				theme = DEFAULT_THEME;
			}
		}
		boolean forceMobileConsole = ctx.getBooleanProperty(RC_PROP_FORCE_MOBILE_CONSOLE);
		boolean isMobile = (forceMobileConsole || isMobile(httpRequest.getHeader("User-Agent")));

		httpRequest.setCharacterEncoding("UTF-8");
		response.setCharacterEncoding("UTF-8");
                response.setHeader("X-Frame-Options", "SAMEORIGIN");
                response.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'");
                response.setHeader("X-XSS-Protection", "1; mode=block");
		RequestWrapper request = new RequestWrapper( httpRequest );
		
		SessionObject sessionObject = null;
		
		String subtitle = "";
		
		HttpSession httpSession = request.getSession( true );
		
		sessionObject = getSessionObject( httpSession );

		synchronized( sessionObject ) {
			
			sessionObject.error = "";
			sessionObject.info = "";
			sessionObject.pageChanged = false;
			sessionObject.themePath = "/themes/susimail/" + theme + '/';
			sessionObject.imgPath = sessionObject.themePath + "images/";
			sessionObject.isMobile = isMobile;
			
			// This must be called to add the attachment before
			// processStateChangeButtons() sends the message
			if( sessionObject.state == STATE_NEW )
				processComposeButtons( sessionObject, request );
		
			int oldState = sessionObject.state;
			processStateChangeButtons( sessionObject, request, isPOST );
			if (isPOST)
				processConfigButtons( sessionObject, request );
			int newState = sessionObject.state;
			if (oldState != newState)
				Debug.debug(Debug.DEBUG, "STATE CHANGE from " + oldState + " to " + newState);
			// Set in web.xml
			//if (oldState == STATE_AUTH && newState != STATE_AUTH) {
			//	int oldIdle = httpSession.getMaxInactiveInterval();
			//	httpSession.setMaxInactiveInterval(60*60*24);  // seconds
			//	int newIdle = httpSession.getMaxInactiveInterval();
			//	Debug.debug(Debug.DEBUG, "Changed idle from " + oldIdle + " to " + newIdle);
			//}
			
			if( sessionObject.state != STATE_AUTH ) {
				if (isPOST)
				       processGenericButtons( sessionObject, request );
			}
			
			if( sessionObject.state == STATE_LIST ) {
				if (isPOST)
					processFolderButtons( sessionObject, request );
				processSortingButtons( sessionObject, request );
				for( Iterator<String> it = sessionObject.folder.currentPageIterator(); it != null && it.hasNext(); ) {
					String uidl = it.next();
					Mail mail = sessionObject.mailCache.getMail( uidl, MailCache.FetchMode.HEADER );
					if( mail != null && mail.error.length() > 0 ) {
						sessionObject.error += mail.error;
						mail.error = "";
					}
				}
			}
			
			if( sessionObject.state == STATE_SHOW ) {
				if (isPOST)
					processMessageButtons( sessionObject, request );
				// ?download=nnn link should be valid in any state
				// but depends on current UIDL
				if (processDownloadLink(sessionObject, request, response)) {
					// download or raw view sent, or 404
					return;
				}
				if (processSaveAsLink(sessionObject, request, response)) {
					// download or sent, or 404
					return;
				}
				// If the last message has just been deleted then
				// sessionObject.state = STATE_LIST and
				// sessionObject.showUIDL = null
				if ( sessionObject.showUIDL != null ) {
					Mail mail = sessionObject.mailCache.getMail( sessionObject.showUIDL, MailCache.FetchMode.ALL );
					if( mail != null && mail.error.length() > 0 ) {
						sessionObject.error += mail.error;
						mail.error = "";
					}
				}
			}
			
			/*
			 * update folder content
			 */
			if( sessionObject.state == STATE_LIST ) {
				// get through cache so we have the disk-only ones too
				String[] uidls = sessionObject.mailCache.getUIDLs();
				if (uidls != null) {
					// TODO why every time?
					sessionObject.folder.setElements(uidls);
				}
			}

				PrintWriter out = response.getWriter();
				
				/*
				 * build subtitle
				 */
				if( sessionObject.state == STATE_AUTH )
					subtitle = _("Login");
				else if( sessionObject.state == STATE_LIST ) {
					// mailbox.getNumMails() forces a connection, don't use it
					// Not only does it slow things down, but a failure causes all our messages to "vanish"
					//subtitle = ngettext("1 Message", "{0} Messages", sessionObject.mailbox.getNumMails());
					subtitle = ngettext("1 Message", "{0} Messages", sessionObject.folder.getSize());
				} else if( sessionObject.state == STATE_SHOW ) {
					Mail mail = sessionObject.mailCache.getMail(sessionObject.showUIDL, MailCache.FetchMode.HEADER);
					if (mail != null && mail.shortSubject != null)
						subtitle = mail.shortSubject; // already HTML encoded
					else
						subtitle = _("Show Message");
				} else if( sessionObject.state == STATE_NEW ) {
					subtitle = _("New Message");
				} else if( sessionObject.state == STATE_CONFIG ) {
					subtitle = _("Configuration");
				}

				response.setContentType( "text/html" );

				/*
				 * write header
				 */
				out.println( "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\">\n<html>\n" +
					"<head>\n" +
					"<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\">\n" +
					"<title>" + _("SusiMail") + " - " + subtitle + "</title>\n" +
					"<link rel=\"stylesheet\" type=\"text/css\" href=\"" + sessionObject.themePath + "susimail.css\">\n" );
				if (sessionObject.isMobile ) {
					out.println( "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=2.0, user-scalable=yes\" />\n" +
						"<link rel=\"stylesheet\" type=\"text/css\" href=\"" + sessionObject.themePath + "mobile.css\" />\n" );
				}
				if(sessionObject.state != STATE_AUTH)
					out.println("<link rel=\"stylesheet\" href=\"/susimail/css/print.css\" type=\"text/css\" media=\"print\" />");
				if (sessionObject.state == STATE_NEW) {
					// TODO cancel if to and body are empty
					out.println(
						"<script type=\"text/javascript\">\n" +
							"window.onbeforeunload = function () {" +
								"return \"" + _("Message has not been sent. Do you want to discard it?") + "\";" +
							"};\n" +
						"</script>"
					);
					out.println("<script src=\"/susimail/js/compose.js\" type=\"text/javascript\"></script>");
				} else if (sessionObject.state == STATE_LIST) {
					out.println("<script src=\"/susimail/js/folder.js\" type=\"text/javascript\"></script>");
				}
				out.print("</head>\n<body" + (sessionObject.state == STATE_LIST ? " onload=\"deleteboxclicked()\">" : ">"));
				out.println(
					"<div class=\"page\"><div class=\"header\"><img class=\"header\" src=\"" + sessionObject.imgPath + "susimail.png\" alt=\"Susimail\"></div>\n" +
					"<form method=\"POST\" enctype=\"multipart/form-data\" action=\"" + myself + "\" accept-charset=\"UTF-8\">" );

				if( sessionObject.error != null && sessionObject.error.length() > 0 ) {
					out.println( "<p class=\"error\">" + quoteHTML(sessionObject.error).replace("\n", "<br>") + "</p>" );
				}
				if( sessionObject.info != null && sessionObject.info.length() > 0 ) {
					out.println( "<p class=\"info\"><b>" + quoteHTML(sessionObject.info).replace("\n", "<br>") + "</b></p>" );
				}
				/*
				 * now write body
				 */
				if( sessionObject.state == STATE_AUTH )
					showLogin( out );
				
				else if( sessionObject.state == STATE_LIST )
					showFolder( out, sessionObject, request );
				
				else if( sessionObject.state == STATE_SHOW )
					showMessage( out, sessionObject );
				
				else if( sessionObject.state == STATE_NEW )
					showCompose( out, sessionObject, request );
				
				else if( sessionObject.state == STATE_CONFIG )
					showConfig(out, sessionObject);
				
				//out.println( "</form><div id=\"footer\"><hr><p class=\"footer\">susimail v0." + version +" " + ( RELEASE ? "release" : "development" ) + " &copy; 2004-2005 <a href=\"mailto:susi23@mail.i2p\">susi</a></div></div></body>\n</html>");				
				out.println( "</form><div class=\"footer\"><hr><p class=\"footer\">susimail &copy; 2004-2005 susi</p></div></div></body>\n</html>");				
				out.flush();
		}
	}

	/**
	 * @param sessionObject
	 * @param response
	 * @param isRaw if true, don't zip it
	 * @return success
	 */
	private static boolean sendAttachment(SessionObject sessionObject, MailPart part,
						 HttpServletResponse response, boolean isRaw)
	{
		boolean shown = false;
		if(part != null) {
			ReadBuffer content = part.buffer;
			
			if( part.encoding != null ) {
					try {
						// why +2 ??
						content = part.decode(2);
					}
					catch (DecodingException e) {
						sessionObject.error += _("Error decoding content: {0}", e.getMessage()) + '\n';
						content = null;
					}
			}
			if(content == null)
				return false;
			if (isRaw) {
				try {
					if (part.type != null)
						response.setContentType(part.type);
					response.setContentLength(content.length);
					// cache-control?
					response.getOutputStream().write(content.content, content.offset, content.length);
					shown = true;
				} catch (IOException e) {
					e.printStackTrace();
				}
			} else {
				ZipOutputStream zip = null;
				try {
					zip = new ZipOutputStream( response.getOutputStream() );
					String name;
					if( part.filename != null )
						name = part.filename;
					else if( part.name != null )
						name = part.name;
					else
						name = "part" + part.hashCode();
					String name2 = sanitizeFilename(name);
					response.setContentType( "application/zip; name=\"" + name2 + ".zip\"" );
					response.addHeader( "Content-Disposition", "attachment; filename=\"" + name2 + ".zip\"" );
					ZipEntry entry = new ZipEntry( name );
					zip.putNextEntry( entry );
					zip.write( content.content, content.offset, content.length );
					zip.closeEntry();
					zip.finish();
					shown = true;
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} finally {
					if ( zip != null)
						try { zip.close(); } catch (IOException ioe) {}
				}
			}
		}
		return shown;
	}

	/**
	 * Send the mail to be saved by the browser
	 *
	 * @param sessionObject
	 * @param response
	 * @return success
	 * @since 0.9.18
	 */
	private static boolean sendMailSaveAs(SessionObject sessionObject, Mail mail,
						 HttpServletResponse response)
	{
		ReadBuffer content = mail.getBody();

		if(content == null)
			return false;
		String name = mail.subject != null ? sanitizeFilename(mail.subject) : "message";
		try {
			response.setContentType("message/rfc822");
			response.setContentLength(content.length);
			// cache-control?
			response.addHeader( "Content-Disposition", "attachment; filename=\"" + name + ".eml\"" );
			response.getOutputStream().write(content.content, content.offset, content.length);
			return true;
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Convert the UTF-8 to ISO-8859-1 suitable for inclusion in a header.
	 * This will result in a bunch of ??? for non-Western languages.
	 *
	 * @param sessionObject
	 * @param response
	 * @return success
	 * @since 0.9.18
	 */
	private static String sanitizeFilename(String name) {
		try {
			name = new String(name.getBytes("ISO-8859-1"), "ISO-8859-1");
		} catch( UnsupportedEncodingException uee ) {}
		// strip control chars?
		name = name.replace('"', '_');
		return name;
	}

	/**
	 * @param sessionObject
	 * @param request
	 * @return success
	 */
	private static boolean sendMail( SessionObject sessionObject, RequestWrapper request )
	{
		boolean ok = true;
		
		String from = request.getParameter( NEW_FROM );
		String to = request.getParameter( NEW_TO );
		String cc = request.getParameter( NEW_CC );
		String bcc = request.getParameter( NEW_BCC );
		String subject = request.getParameter( NEW_SUBJECT, _("no subject") );
		String text = request.getParameter( NEW_TEXT, "" );

		boolean fixed = Boolean.parseBoolean(Config.getProperty( CONFIG_SENDER_FIXED, "true" ));
		if (fixed) {
			String domain = Config.getProperty( CONFIG_SENDER_DOMAIN, "mail.i2p" );
			from = "<" + sessionObject.user + "@" + domain + ">";
		}
		ArrayList<String> toList = new ArrayList<String>();
		ArrayList<String> ccList = new ArrayList<String>();
		ArrayList<String> bccList = new ArrayList<String>();
		ArrayList<String> recipients = new ArrayList<String>();
		
		String sender = null;
		
		if( from == null || !Mail.validateAddress( from ) ) {
			ok = false;
			sessionObject.error += _("Found no valid sender address.") + '\n';
		}
		else {
			sender = Mail.getAddress( from );
			if( sender == null || sender.length() == 0 ) {
				ok = false;
				sessionObject.error += _("Found no valid address in \\''{0}\\''.", quoteHTML( from )) + '\n';
			}
		}
		
		ok = Mail.getRecipientsFromList( toList, to, ok );
		ok = Mail.getRecipientsFromList( ccList, cc, ok );
		ok = Mail.getRecipientsFromList( bccList, bcc, ok );

		recipients.addAll( toList );
		recipients.addAll( ccList );
		recipients.addAll( bccList );
		
		String bccToSelf = request.getParameter( NEW_BCC_TO_SELF );
		boolean toSelf = "1".equals(bccToSelf);
		// save preference in session
		sessionObject.bccToSelf = toSelf;
		if (toSelf)
			recipients.add( sender );
		
		if( toList.isEmpty() ) {
			ok = false;
			sessionObject.error += _("No recipients found.") + '\n';
		}
		Encoding qp = EncodingFactory.getEncoding( "quoted-printable" );
		Encoding hl = EncodingFactory.getEncoding( "HEADERLINE" );
		
		if( qp == null ) {
			ok = false;
			// can't happen, don't translate
			sessionObject.error += "Internal error: Quoted printable encoder not available.";
		}
		
		if( hl == null ) {
			ok = false;
			// can't happen, don't translate
			sessionObject.error += "Internal error: Header line encoder not available.";
		}

		if( ok ) {
			StringBuilder body = new StringBuilder();
			body.append( "From: " + from + "\r\n" );
			Mail.appendRecipients( body, toList, "To: " );
			Mail.appendRecipients( body, ccList, "To: " );
			body.append( "Subject: " );
			try {
				body.append( hl.encode( subject ) );
			} catch (EncodingException e) {
				ok = false;
				sessionObject.error += e.getMessage();
			}
			String boundary = "_="+(int)(Math.random()*Integer.MAX_VALUE)+""+(int)(Math.random()*Integer.MAX_VALUE);
			boolean multipart = false;
			if( sessionObject.attachments != null && !sessionObject.attachments.isEmpty() ) {
				multipart = true;
				body.append( "\r\nMIME-Version: 1.0\r\nContent-type: multipart/mixed; boundary=\"" + boundary + "\"\r\n\r\n" );
			}
			else {
				body.append( "\r\nMIME-Version: 1.0\r\nContent-type: text/plain; charset=\"utf-8\"\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n" );
			}
			try {
				if( multipart )
					body.append( "--" + boundary + "\r\nContent-type: text/plain; charset=\"utf-8\"\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n" );
				body.append( qp.encode( text ) );
			} catch (EncodingException e) {
				ok = false;
				sessionObject.error += e.getMessage();
			}

			if( multipart ) {
				for( Attachment attachment : sessionObject.attachments ) {
					body.append( "\r\n--" + boundary + "\r\nContent-type: " + attachment.getContentType() + "\r\nContent-Disposition: attachment; filename=\"" + attachment.getFileName() + "\"\r\nContent-Transfer-Encoding: " + attachment.getTransferEncoding() + "\r\n\r\n" );
					body.append( attachment.getData() );
				}
				body.append( "\r\n--" + boundary + "--\r\n" );
			}
			
			// TODO set to the StringBuilder instead so SMTP can replace() in place
			sessionObject.sentMail = body.toString();	
			
			if( ok ) {
				SMTPClient relay = new SMTPClient();
				if( relay.sendMail( sessionObject.host, sessionObject.smtpPort,
						sessionObject.user, sessionObject.pass,
						sender, recipients.toArray(), sessionObject.sentMail ) ) {
					
					sessionObject.info += _("Mail sent.");
					
					sessionObject.sentMail = null;	
					if( sessionObject.attachments != null )
						sessionObject.attachments.clear();
				}
				else {
						ok = false;
						sessionObject.error += relay.error;
				}
			}
		}
		return ok;
	}

	/**
	 * 
	 */
	@Override
	public void doGet( HttpServletRequest request, HttpServletResponse response )
	throws IOException, ServletException
	{
		processRequest( request, response, false );		
	}

	/**
	 * 
	 */
	@Override
	public void doPost( HttpServletRequest request, HttpServletResponse response )
	throws IOException, ServletException
	{
		processRequest( request, response, true );
	}

	/**
	 * 
	 * @param out
	 * @param sessionObject
	 * @param request
	 */
	private static void showCompose( PrintWriter out, SessionObject sessionObject, RequestWrapper request )
	{
		out.println("<div class=\"topbuttons\">");
		out.println( button( SEND, _("Send") ) + spacer +
				button( CANCEL, _("Cancel") ));
		out.println("</div>");
		//if (Config.hasConfigFile())
		//	out.println(button( RELOAD, _("Reload Config") ) + spacer);
		//out.println(button( LOGOUT, _("Logout") ) );

		String from = request.getParameter( NEW_FROM );
		boolean fixed = Boolean.parseBoolean(Config.getProperty( CONFIG_SENDER_FIXED, "true" ));
		
		if (from == null || !fixed) {
			String user = sessionObject.user;
			String name = Config.getProperty(CONFIG_SENDER_NAME);
			if (name != null) {
				name = name.trim();
				if (name.contains(" "))
					from = '"' + name + "\" ";
				else
					from = name + ' ';
			} else {
				from = "";
			}
			if (user.contains("@")) {
				from += '<' + user + '>';
			} else {
				String domain = Config.getProperty( CONFIG_SENDER_DOMAIN, "mail.i2p" );
				if (from.length() == 0)
					from = user + ' ';
				from += '<' + user + '@' + domain + '>';
			}
		}
		
		String to = request.getParameter( NEW_TO, sessionObject.replyTo != null ? sessionObject.replyTo : "" );
		String cc = request.getParameter( NEW_CC, sessionObject.replyCC != null ? sessionObject.replyCC : "" );
		String bcc = request.getParameter( NEW_BCC, "" );
		String subject = request.getParameter( NEW_SUBJECT, sessionObject.subject != null ? sessionObject.subject : "" );
		String text = request.getParameter( NEW_TEXT, sessionObject.body != null ? sessionObject.body : "" );
		sessionObject.replyTo = null;
		sessionObject.replyCC = null;
		sessionObject.subject = null;
		sessionObject.body = null;
		
		out.println( "<table cellspacing=\"0\" cellpadding=\"5\">\n" +
				"<tr><td colspan=\"2\" align=\"center\"><hr></td></tr>\n" +
				"<tr><td align=\"right\">" + _("From") + ":</td><td align=\"left\"><input type=\"text\" size=\"80\" name=\"" + NEW_FROM + "\" value=\"" + quoteHTML(from) + "\" " + ( fixed ? "disabled" : "" ) +"></td></tr>\n" +
				"<tr><td align=\"right\">" + _("To") + ":</td><td align=\"left\"><input type=\"text\" size=\"80\" name=\"" + NEW_TO + "\" value=\"" + quoteHTML(to) + "\"></td></tr>\n" +
				"<tr><td align=\"right\">" + _("Cc") + ":</td><td align=\"left\"><input type=\"text\" size=\"80\" name=\"" + NEW_CC + "\" value=\"" + quoteHTML(cc) + "\"></td></tr>\n" +
				"<tr><td align=\"right\">" + _("Bcc") + ":</td><td align=\"left\"><input type=\"text\" size=\"80\" name=\"" + NEW_BCC + "\" value=\"" + quoteHTML(bcc) + "\"></td></tr>\n" +
				"<tr><td align=\"right\">" + _("Bcc to self") + ": </td><td align=\"left\"><input type=\"checkbox\" class=\"optbox\" name=\"" + NEW_BCC_TO_SELF + "\" value=\"1\" " + (sessionObject.bccToSelf ? "checked" : "" ) + "></td></tr>\n" +
				"<tr><td align=\"right\">" + _("Subject") + ":</td><td align=\"left\"><input type=\"text\" size=\"80\" name=\"" + NEW_SUBJECT + "\" value=\"" + quoteHTML(subject) + "\"></td></tr>\n" +
				"<tr><td colspan=\"2\" align=\"center\"><textarea cols=\"" + Config.getProperty( CONFIG_COMPOSER_COLS, 80 )+ "\" rows=\"" + Config.getProperty( CONFIG_COMPOSER_ROWS, 10 )+ "\" name=\"" + NEW_TEXT + "\">" + text + "</textarea>" +
				"<tr class=\"bottombuttons\"><td colspan=\"2\" align=\"center\"><hr></td></tr>\n" +
				"<tr class=\"bottombuttons\"><td align=\"right\">" + _("Add Attachment") + ":</td><td align=\"left\"><input type=\"file\" size=\"50%\" name=\"" + NEW_FILENAME + "\" value=\"\"></td></tr>" +
				// TODO disable/hide in JS if no file selected
				"<tr class=\"bottombuttons\"><td>&nbsp;</td><td align=\"left\">" + button(NEW_UPLOAD, _("Add another attachment")) + "</td></tr>");
		
		if( sessionObject.attachments != null && !sessionObject.attachments.isEmpty() ) {
			boolean wroteHeader = false;
			for( Attachment attachment : sessionObject.attachments ) {
				if( !wroteHeader ) {
					out.println("<tr><td align=\"right\">" + _("Attachments") + ":</td>");
					wroteHeader = true;
				} else {
					out.println("<tr><td align=\"right\">&nbsp;</td>");
				}
				out.println("<td align=\"left\"><input type=\"checkbox\" class=\"optbox\" name=\"check" + attachment.hashCode() + "\" value=\"1\">&nbsp;" + quoteHTML(attachment.getFileName()) + "</td></tr>");
			}
			// TODO disable in JS if none selected
			out.println("<tr class=\"bottombuttons\"><td>&nbsp;</td><td align=\"left\">" +
			            button( DELETE_ATTACHMENT, _("Delete selected attachments") ) +
				    "</td></tr>");
		}
		out.println( "</table>" );
	}

	/**
	 * 
	 * @param out
	 */
	private static void showLogin( PrintWriter out )
	{
		boolean fixed = Boolean.parseBoolean(Config.getProperty( CONFIG_PORTS_FIXED, "true" ));
		String host = Config.getProperty( CONFIG_HOST, DEFAULT_HOST );
		String pop3 = Config.getProperty( CONFIG_PORTS_POP3, "" + DEFAULT_POP3PORT );
		String smtp = Config.getProperty( CONFIG_PORTS_SMTP, "" + DEFAULT_SMTPPORT );
		
		out.println( "<table cellspacing=\"3\" cellpadding=\"5\">\n" +
			// current postman hq length limits 16/12, new postman version 32/32
			"<tr><td align=\"right\" width=\"30%\">" + _("User") + "</td><td width=\"40%\" align=\"left\"><input type=\"text\" size=\"32\" name=\"" + USER + "\" value=\"" + "\"> @mail.i2p</td></tr>\n" +
			"<tr><td align=\"right\" width=\"30%\">" + _("Password") + "</td><td width=\"40%\" align=\"left\"><input type=\"password\" size=\"32\" name=\"pass\" value=\"" + "\"></td></tr>\n");
		// which is better?
		//if (!fixed) {
		if (true) {
		    out.println(
			"<tr><td align=\"right\" width=\"30%\">" + _("Host") + "</td><td width=\"40%\" align=\"left\"><input type=\"text\" size=\"32\" name=\"" + HOST +"\" value=\"" + quoteHTML(host) + "\"" + ( fixed ? " disabled" : "" ) + "></td></tr>\n" +
			"<tr><td align=\"right\" width=\"30%\">" + _("POP3 Port") + "</td><td width=\"40%\" align=\"left\"><input type=\"text\" style=\"text-align: right;\" size=\"5\" name=\"" + POP3 +"\" value=\"" + quoteHTML(pop3) + "\"" + ( fixed ? " disabled" : "" ) + "></td></tr>\n" +
			"<tr><td align=\"right\" width=\"30%\">" + _("SMTP Port") + "</td><td width=\"40%\" align=\"left\"><input type=\"text\" style=\"text-align: right;\" size=\"5\" name=\"" + SMTP +"\" value=\"" + quoteHTML(smtp) + "\"" + ( fixed ? " disabled" : "" ) + "></td></tr>\n");
		}
		out.println(
			"<tr><td colspan=\"2\">&nbsp;</td></tr>\n" +
			"<tr><td></td><td align=\"left\">" + button( LOGIN, _("Login") ) + spacer +
			 button(OFFLINE, _("Read Mail Offline") ) +
			 //spacer +
			 //" <input class=\"cancel\" type=\"reset\" value=\"" + _("Reset") + "\">" +
			 spacer +
			 button(CONFIGURE, _("Settings")) +
			"</td></tr>\n" +
			"<tr><td colspan=\"2\">&nbsp;</td></tr>\n" +
			"<tr><td></td><td align=\"left\"><a href=\"http://hq.postman.i2p/?page_id=14\">" + _("Learn about I2P mail") + "</a></td></tr>\n" +
			"<tr><td></td><td align=\"left\"><a href=\"http://hq.postman.i2p/?page_id=16\">" + _("Create Account") + "</a></td></tr>\n" +
			"</table>");
	}

	/**
	 * 
	 * @param out
	 * @param sessionObject
	 * @param request
	 */
	private static void showFolder( PrintWriter out, SessionObject sessionObject, RequestWrapper request )
	{
		out.println("<div class=\"topbuttons\">");
		out.println( button( NEW, _("New") ) + spacer);
			// In theory, these are valid and will apply to the first checked message,
			// but that's not obvious and did it work?
			//button( REPLY, _("Reply") ) +
			//button( REPLYALL, _("Reply All") ) +
			//button( FORWARD, _("Forward") ) + spacer +
			//button( DELETE, _("Delete") ) + spacer +
		out.println(button( REFRESH, _("Check Mail") ) + spacer);
		//if (Config.hasConfigFile())
		//	out.println(button( RELOAD, _("Reload Config") ) + spacer);
		out.println(button( LOGOUT, _("Logout") ));
		if (sessionObject.folder.getPages() > 1)
			showPageButtons(out, sessionObject.folder);
		out.println("</div>");


		String curSort = sessionObject.folder.getCurrentSortBy();
		Folder.SortOrder curOrder = sessionObject.folder.getCurrentSortingDirection();
		out.println("<table id=\"mailbox\" cellspacing=\"0\" cellpadding=\"5\">\n" +
			"<tr><td colspan=\"9\"><hr></td></tr>\n<tr>" +
			thSpacer +
			thSpacer + "<th>" + sortHeader( SORT_SENDER, _("From"), sessionObject.imgPath, curSort, curOrder ) + "</th>" +
			thSpacer + "<th>" + sortHeader( SORT_SUBJECT, _("Subject"), sessionObject.imgPath, curSort, curOrder ) + "</th>" +
			thSpacer + "<th>" + sortHeader( SORT_DATE, _("Date"), sessionObject.imgPath, curSort, curOrder ) +
			//sortHeader( SORT_ID, "", sessionObject.imgPath ) +
			"</th>" +
			thSpacer + "<th>" + sortHeader( SORT_SIZE, _("Size"), sessionObject.imgPath, curSort, curOrder ) + "</th></tr>" );
		int bg = 0;
		int i = 0;
		for( Iterator<String> it = sessionObject.folder.currentPageIterator(); it != null && it.hasNext(); ) {
			String uidl = it.next();
			Mail mail = sessionObject.mailCache.getMail( uidl, MailCache.FetchMode.HEADER );
			if (mail == null) {
				i++;
				continue;
			}
			String type;
			if (mail.isSpam())
				type = "linkspam";
			else if (mail.isNew())
				type = "linknew";
			else
				type = "linkold";
			String link = "<a href=\"" + myself + "?" + SHOW + "=" + i + "\" class=\"" + type + "\">";
			String jslink = " onclick=\"document.location='" + myself + '?' + SHOW + '=' + i + "';\" ";
			
			boolean idChecked = false;
			String checkId = sessionObject.pageChanged ? null : request.getParameter( "check" + i );
			
			if( checkId != null && checkId.equals("1"))
				idChecked = true;
			
			if( sessionObject.markAll )
				idChecked = true;
			if( sessionObject.invert )
				idChecked = !idChecked;
			if( sessionObject.clear )
				idChecked = false;

			//Debug.debug( Debug.DEBUG, "check" + i + ": checkId=" + checkId + ", idChecked=" + idChecked + ", pageChanged=" + sessionObject.pageChanged +
			//		", markAll=" + sessionObject.markAll +
			//		", invert=" + sessionObject.invert +
			//		", clear=" + sessionObject.clear );
			out.println( "<tr class=\"list" + bg + "\">" +
					"<td><input type=\"checkbox\" class=\"optbox\" name=\"check" + i + "\" value=\"1\"" + 
					" onclick=\"deleteboxclicked();\" " +
					( idChecked ? "checked" : "" ) + ">" + "</td><td " + jslink + ">" +
					(mail.isNew() ? "<img src=\"/susimail/icons/flag_green.png\" alt=\"\" title=\"" + _("Message is new") + "\">" : "&nbsp;") + "</td><td " + jslink + ">" +
                                        // mail.shortSender and mail.shortSubject already html encoded
					link + mail.shortSender + "</a></td><td " + jslink + ">" +
					(mail.hasAttachment() ? "<img src=\"/susimail/icons/attach.png\" alt=\"\" title=\"" + _("Message has an attachment") + "\">" : "&nbsp;") + "</td><td " + jslink + ">" +
					link + mail.shortSubject + "</a></td><td " + jslink + ">" +
					(mail.isSpam() ? "<img src=\"/susimail/icons/flag_red.png\" alt=\"\" title=\"" + _("Message is spam") + "\">" : "&nbsp;") + "</td><td " + jslink + ">" +
					// don't let date get split across lines
					mail.localFormattedDate.replace(" ", "&nbsp;") + "</td><td " + jslink + ">&nbsp;</td><td align=\"right\" " + jslink + ">" +
					((mail.getSize() > 0) ? (DataHelper.formatSize2(mail.getSize()) + 'B') : "???") + "</td></tr>" );
			bg = 1 - bg;
			i++;
		}
		if (i == 0)
			out.println("<tr><td colspan=\"9\" align=\"center\"><i>" + _("No messages") + "</i></td></tr>\n</table>");
		if (i > 0) {
			out.println( "<tr class=\"bottombuttons\"><td colspan=\"9\"><hr></td></tr>");
			if (sessionObject.folder.getPages() > 1 && i > 30) {
				// show the buttons again if page is big
				out.println("<tr class=\"bottombuttons\"><td colspan=\"9\" align=\"center\">");
				showPageButtons(out, sessionObject.folder);
				out.println("</td></tr>");
			}
			out.println("<tr class=\"bottombuttons\"><td colspan=\"5\" align=\"left\">");
			if( sessionObject.reallyDelete ) {
				// TODO ngettext
				out.println("<p class=\"error\">" + _("Really delete the marked messages?") +
						"</p>" + button( REALLYDELETE, _("Yes, really delete them!") ) +
						"<br>" + button( CLEAR, _("Cancel")));
			} else {
				out.println(button( DELETE, _("Delete Selected") ) + "<br>");
				out.print(
					button( MARKALL, _("Mark All") ) +
					"&nbsp;" +
					button( CLEAR, _("Clear All") ));
					//"<br>" + 
					//button( INVERT, _("Invert Selection") ) +
					//"<br>");
			}
			out.print("</td>\n<td colspan=\"4\" align=\"right\">");
			// moved to config page
			//out.print(
			//	_("Page Size") + ":&nbsp;<input type=\"text\" style=\"text-align: right;\" name=\"" + PAGESIZE + "\" size=\"4\" value=\"" +  sessionObject.folder.getPageSize() + "\">" +
			//	"&nbsp;" + 
			//	button( SETPAGESIZE, _("Set") ) );
			out.print("<br>");
			out.print(button(CONFIGURE, _("Settings")));
			out.println("</td></tr>");
		}
		out.println( "</table>");
	}

	/**
	 *  first prev next last
	 */
	private static void showPageButtons(PrintWriter out, Folder folder) {
		out.println(
			"<br>" +
			( folder.isFirstPage() ?
						button2( FIRSTPAGE, _("First") ) + "&nbsp;" + button2( PREVPAGE, _("Previous") ) :
						button( FIRSTPAGE, _("First") ) + "&nbsp;" + button( PREVPAGE, _("Previous") ) ) +
			" &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;" +
			_("Page {0} of {1}", folder.getCurrentPage(), folder.getPages()) +
			"&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; " +
			( folder.isLastPage() ? 
						button2( NEXTPAGE, _("Next") ) + "&nbsp;" + button2( LASTPAGE, _("Last") ) :
						button( NEXTPAGE, _("Next") ) + "&nbsp;" + button( LASTPAGE, _("Last") ) )
		);
	}

	/**
	 * 
	 * @param out
	 * @param sessionObject
	 */
	private static void showMessage( PrintWriter out, SessionObject sessionObject )
	{
		if( sessionObject.reallyDelete ) {
			out.println( "<p class=\"error\">" + _("Really delete this message?") + " " + button( REALLYDELETE, _("Yes, really delete it!") ) + "</p>" );
		}
		Mail mail = sessionObject.mailCache.getMail( sessionObject.showUIDL, MailCache.FetchMode.ALL );
		if(!RELEASE && mail != null && mail.hasBody()) {
			out.println( "<!--" );
			out.println( "Debug: Mail header and body follow");
			// FIXME encoding, escaping --, etc... but disabled.
			ReadBuffer body = mail.getBody();
			out.println( quoteHTML( new String(body.content, body.offset, body.length ) ) );
			out.println( "-->" );
		}
		out.println("<div class=\"topbuttons\">");
		out.println( button( NEW, _("New") ) + spacer +
			button( REPLY, _("Reply") ) +
			button( REPLYALL, _("Reply All") ) +
			button( FORWARD, _("Forward") ) + spacer +
			button( SAVE_AS, _("Save As") ) + spacer);
		if (sessionObject.reallyDelete)
			out.println(button2(DELETE, _("Delete")));
		else
			out.println(button(DELETE, _("Delete")));
		out.println("<br>" +
			( sessionObject.folder.isFirstElement( sessionObject.showUIDL ) ? button2( PREV, _("Previous") ) : button( PREV, _("Previous") ) ) + spacer +
			button( LIST, _("Back to Folder") ) + spacer +
			( sessionObject.folder.isLastElement( sessionObject.showUIDL ) ? button2( NEXT, _("Next") ) : button( NEXT, _("Next") ) ));
		out.println("</div>");
		//if (Config.hasConfigFile())
		//	out.println(button( RELOAD, _("Reload Config") ) + spacer);
		//out.println(button( LOGOUT, _("Logout") ) );
		if( mail != null ) {
			out.println( "<table cellspacing=\"0\" cellpadding=\"5\">\n" +
					"<tr><td colspan=\"2\" align=\"center\"><hr></td></tr>\n" +
					"<tr class=\"mailhead\"><td align=\"right\" valign=\"top\">" + _("From") +
					":</td><td align=\"left\">" + quoteHTML( mail.sender ) + "</td></tr>\n" +
					"<tr class=\"mailhead\"><td align=\"right\" valign=\"top\">" + _("Subject") +
					":</td><td align=\"left\">" + quoteHTML( mail.formattedSubject ) + "</td></tr>\n" +
					"<tr class=\"mailhead\"><td align=\"right\" valign=\"top\">" + _("Date") +
					":</td><td align=\"left\">" + mail.quotedDate + "</td></tr>\n" +
					"<tr><td colspan=\"2\" align=\"center\"><hr></td></tr>" );
			if( mail.hasPart()) {
				mail.setNew(false);
				showPart( out, mail.getPart(), 0, SHOW_HTML );
			}
			else {
				out.println( "<tr class=\"mailbody\"><td colspan=\"2\" align=\"center\"><p class=\"error\">" + _("Could not fetch mail body.") + "</p></td></tr>" );
			}
		}
		else {
			out.println( "<tr class=\"mailbody\"><td colspan=\"2\" align=\"center\"><p class=\"error\">" + _("Could not fetch mail.") + "</p></td></tr>" );
		}
		out.println( "<tr><td colspan=\"2\" align=\"center\"><hr></td></tr>\n</table>" );
	}

	/**
	 *  Simple configure page
	 *
	 *  @since 0.9.13
	 */
	private static void showConfig(PrintWriter out, SessionObject sessionObject) {
		int sz;
		if (sessionObject.folder != null)
			sz = sessionObject.folder.getPageSize();
		else
			sz = Config.getProperty(Folder.PAGESIZE, Folder.DEFAULT_PAGESIZE);
		out.println("<div class=\"topbuttons\">");
		out.println(
			_("Folder Page Size") + ":&nbsp;<input type=\"text\" style=\"text-align: right;\" name=\"" + PAGESIZE +
			"\" size=\"4\" value=\"" +  sz + "\">" +
			"&nbsp;" + 
			button( SETPAGESIZE, _("Set") ) );
		out.println("<p>");
		out.println("</div>");
		out.print(_("Advanced Configuration"));
		Properties config = Config.getProperties();
		out.print(":</p><textarea cols=\"80\" rows=\"" + Math.max(8, config.size() + 2) + "\" spellcheck=\"false\" name=\"" + CONFIG_TEXT + "\">");
		for (Map.Entry<Object, Object> e : config.entrySet()) {
			out.print(quoteHTML(e.getKey().toString()));
			out.print('=');
			out.println(quoteHTML(e.getValue().toString()));
		}
		out.println("</textarea>");
		out.println("<div id=\"bottombuttons\">");
		out.println("<br>");
		out.println(button(SAVE, _("Save Configuration")));
		out.println(button(CANCEL, _("Cancel")));
		out.println("</div>");
	}

	/** translate */
	private static String _(String s) {
		return Messages.getString(s);
	}

	/** translate */
	private static String _(String s, Object o) {
		return Messages.getString(s, o);
	}

	/** translate */
	private static String _(String s, Object o, Object o2) {
		return Messages.getString(s, o, o2);
	}
	
	/** translate */
    private static String ngettext(String s, String p, int n) {
        return Messages.getString(n, s, p);
    }

    /**
     * Get all themes
     * @return String[] -- Array of all the themes found.
     */
    private static String[] getThemes() {
            String[] themes = null;
            // "docs/themes/susimail/"
            File dir = new File(I2PAppContext.getGlobalContext().getBaseDir(), "docs/themes/susimail");
            FileFilter fileFilter = new FileFilter() { public boolean accept(File file) { return file.isDirectory(); } };
            // Walk the themes dir, collecting the theme names, and append them to the map
            File[] dirnames = dir.listFiles(fileFilter);
            if (dirnames != null) {
                themes = new String[dirnames.length];
                for(int i = 0; i < dirnames.length; i++) {
                    themes[i] = dirnames[i].getName();
                }
            }
            // return the map.
            return themes;
    }
}
