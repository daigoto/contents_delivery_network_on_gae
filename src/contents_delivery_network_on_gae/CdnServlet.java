package contents_delivery_network_on_gae;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;

import com.google.appengine.api.memcache.*;
import com.google.appengine.api.urlfetch.*;
import com.google.appengine.repackaged.com.google.protobuf.ByteString;
import com.google.apphosting.api.ApiProxy.*;
import javax.servlet.*;
import javax.servlet.http.*;

@SuppressWarnings({ "serial", "unused" })
public class CdnServlet extends HttpServlet {
	private MemcacheService memcacheService = MemcacheServiceFactory.getMemcacheService();
	private Hashtable<String,String> etagtable = null;
	private Hashtable<String,String> modtable = null;
	private Hashtable<String,String> contenttable = null;
	@SuppressWarnings({ "deprecation", "unchecked" })
	public void doGet(HttpServletRequest req, HttpServletResponse resp)
			throws IOException {

		ServletContext sc = getServletContext();
		String requestpath = "";
		try{
			etagtable    = (Hashtable<String,String>)sc.getAttribute("etags");
			modtable     = (Hashtable<String,String>)sc.getAttribute("mods");
			contenttable = (Hashtable<String,String>)sc.getAttribute("contenttype");
		}catch(Exception e){}
		if(etagtable == null){
			etagtable = new Hashtable<String,String>();
		}
		if(modtable == null){
			modtable = new Hashtable<String,String>();
		}
		if(contenttable == null){
			contenttable = new Hashtable<String,String>();
		}
		try{
			requestpath = req.getRequestURI().toLowerCase();
			Pattern pattern = Pattern.compile("^/c/");
			Matcher matcher = pattern.matcher(requestpath);
			requestpath = matcher.replaceFirst("/");
			String etag = req.getHeader("If-None-Match");
			String lastmod = req.getHeader("If-Modified-Since");
			//304判定
			if(etag != null && etag.equals(etagtable.get(requestpath))){
				throw new Status304Exception();
			} else if(lastmod != null && lastmod.equals(modtable.get(requestpath))){
				throw new Status304Exception();
			}
			Hashtable<String,String> cache = null;
			try{
				cache = (Hashtable<String,String>)memcacheService.get(requestpath);
			}catch(Exception e){}
			if(cache != null){
				if(etag != null && etag.equals(cache.get("Etag"))){
					etagtable.put(requestpath, cache.get("Etag"));
					throw new Status304Exception();
				}
				if(lastmod != null && lastmod.equals(cache.get("Last-Modified"))){
					modtable.put(requestpath, cache.get("Last-Modified"));
					throw new Status304Exception();
				}
			}else{
				cache = new Hashtable<String,String>();
			}
			//404判定
			String surfix = requestpath.substring(requestpath.lastIndexOf(".")+1);
			String surfixes = getInitParameter("surfix");
			String contenttype = "";
			String[] surfixlist = surfixes.split(",");
			for(String sur : surfixlist){
				String[] surlist = sur.split("=");
				if(surlist[0].equalsIgnoreCase(surfix)){
					contenttype = surlist[1];
					break;
				}
			}
			if(contenttype.length() < 1){
				throw new Status404Exception("surfix is invalid");
			}
			responseFileContents(req, resp, requestpath, cache, contenttype);
		} catch(Status304Exception e304) {
			resp.reset();
			//resp.setHeader("Content-Type", contenttable.get(requestpath));
			resp.setStatus(304, "Not Modified");
		} catch(Status404Exception e404) {
			resp.reset();
			//resp.setHeader("Content-Type", contenttable.get(requestpath));
			resp.setStatus(404, "Not Found");
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		} finally {
			sc.setAttribute("etags", etagtable);
			sc.setAttribute("mods", modtable);
			sc.setAttribute("contenttype", contenttable);
		}
	}

	private void responseFileContents(HttpServletRequest req, HttpServletResponse resp, String requestpath, Hashtable<String,String> cache, String contenttype)
		throws Status304Exception, Status404Exception, NoSuchAlgorithmException, IOException {
		long gap = -1;
		//コンテンツの有効期限チェック
		try{
			if(cache.get("Last-Modified") != null){
				Date dt = DateFormat.getDateInstance().parse(cache.get("Last-Modified"));
				gap = ((new Date().getTime()) - dt.getTime())/(1000*3600*24);
			}
		}catch(ParseException e){
		}

		Hashtable<String,String> headers = new Hashtable<String,String>();
		byte[] c = null;
		try{
			c = (byte[])memcacheService.get("c-"+requestpath);
		}catch(Exception e){}
		String lastmod = "";
		String etag = "";
		if(c != null && gap >= 0 && gap < Integer.parseInt(getInitParameter("expireDays"))){
			headers.put("Content-Type", cache.get("Content-Type"));
			headers.put("Last-Modified", cache.get("Last-Modified"));
			headers.put("Etag", cache.get("Etag"));
		}else{
			String scheme = req.getScheme();
			if(scheme != "http" && scheme != "https"){
				throw new Status404Exception();
			}
			URLFetchService fetchService = (URLFetchService) URLFetchServiceFactory.getURLFetchService();
			HTTPRequest httpRequest = new HTTPRequest(
					new URL(scheme + "://" + getInitParameter("origindomain") + requestpath),
					HTTPMethod.GET,
					FetchOptions.Builder.disallowTruncate().followRedirects()
			);
			HTTPResponse httpResponse = fetchService.fetch(httpRequest);
			List<HTTPHeader> headerlist = httpResponse.getHeaders();
			headers.clear();
			//etag
			MessageDigest md = MessageDigest.getInstance("MD5");
			md.reset();
			md.update((requestpath+lastmod).getBytes());
			byte[] hash = md.digest();
			StringBuffer sb = new StringBuffer();
			for(int i= 0; i < hash.length; i++){
				sb.append(Integer.toHexString( (hash[i]>> 4) & 0x0F ) );
				sb.append(Integer.toHexString( hash[i] & 0x0F ) );
			}
			etag = "\"" + sb.toString() + "\"";
			//expire
			SimpleDateFormat df = new SimpleDateFormat("EEE, dd MMM yyyy hh:mm:ss");
			Calendar calendar = Calendar.getInstance();
			c = httpResponse.getContent();
			headers.put("Content-Type", contenttype);
			lastmod = df.format(calendar.getTime()) + " GMT";
			headers.put("Last-Modified", lastmod);
			headers.put("Etag", etag);
			calendar.add(Calendar.DAY_OF_MONTH, Integer.parseInt(getInitParameter("expireDays")));
			headers.put("Expires", df.format(calendar.getTime()) + " GMT");

			//To memcache
			memcacheService.put(requestpath, headers);
			memcacheService.put("c-"+requestpath, c);
			//To SetvletContext
			etagtable.put(requestpath, etag);
			modtable.put(requestpath, lastmod);
			contenttable.put(requestpath, contenttype);
		}

		resp.setHeader("Content-type", headers.get("Content-Type"));
		resp.setHeader("Expires", headers.get("Expires"));
		resp.setHeader("Etag", headers.get("Etag"));
		resp.setHeader("Last-Modified", headers.get("Last-Modified"));
		ServletOutputStream st = resp.getOutputStream();
		st.write(c);
		st.close();
	}
}
