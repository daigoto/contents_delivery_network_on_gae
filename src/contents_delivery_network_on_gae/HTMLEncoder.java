package contents_delivery_network_on_gae;

public class HTMLEncoder {
	public static String encode(String val){
		if (val == null) return("");
		return val.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
