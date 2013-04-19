package scalacg.probe;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import probe.CallGraph;
import probe.Jui;
import probe.ProbeClass;
import probe.ProbeMethod;

public class CallGraphView extends Jui {

	private Map<ProbeClass, String> classIdMap = new HashMap<ProbeClass, String>();
	private Map<String, ProbeClass> idClassMap = new HashMap<String, ProbeClass>();
	private Map<ProbeMethod, String> methodIdMap = new HashMap<ProbeMethod, String>();
	private Map<String, ProbeMethod> idMethodMap = new HashMap<String, ProbeMethod>();
	static int nextId = 1;

	public static void usage() {
		System.out
				.println("Usage: java -classpath callgraph-plugin.jar scalacg.probe.CallGraphView [options] supergraph.gxl [subgraph.gxl]");
		System.out.println("  -port p: listen on port p (default: 8088)");
		System.exit(1);
	}

	public static final void main(String[] args) {
		new CallGraphView().run(args);
	}

	@SuppressWarnings("rawtypes")
	private Collection subgraphReachables;
	private CallGraph supergraph;
	private String supergraphName;
	private String subgraphName;

	@Override
	public void run(String[] args) {
		List<String> newArgs = new ArrayList<String>();
		System.out.println("reading graph ...");

		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("-port")) {
				newArgs.add(args[i]);
				i++;
				newArgs.add(args[i]);
			} else {
				if (supergraph == null) {
					supergraph = readCallGraph(args[i]);
					supergraphName = args[i];
				} else if (subgraphReachables == null) {
					subgraphReachables = readCallGraph(args[i]).findReachables();
					subgraphName = args[i];
				} else {
					usage();
				}
			}
		}
		if (supergraph == null)
			usage();
		for (probe.CallEdge edge : supergraph.edges()) {
			addMethod(edge.src());
			addMethod(edge.dst());
		}
		for (ProbeMethod method : supergraph.entryPoints()) {
			addMethod(method);
		}
		System.out.println("starting server ...");
		super.run((String[]) newArgs.toArray(new String[0]));
	}

	@SuppressWarnings("rawtypes")
	@Override
	public String process(Map args) {
		if (args.containsKey("node")) {
			return nodePage((String) args.get("node"));
		} else if (args.containsKey("search")) {
			return search((String) args.get("search"));
		} else {
			return rootNodePage();
		}
	}

	/**
	 * Create the HTML page corresponding the given node.
	 * 
	 * @param nodeid
	 * @return
	 */
	public String nodePage(String nodeid) {
		if (nodeid.equalsIgnoreCase("root")) {
			return rootNodePage();
		} else if (nodeid.startsWith("c")) {
			return nodePage((ProbeClass) idClassMap.get(nodeid));
		} else if (nodeid.startsWith("m")) {
			return nodePage((ProbeMethod) idMethodMap.get(nodeid));
		} else {
			throw new RuntimeException("bad node id");
		}
	}

	/**
	 * Create the HTML page for the root node of the call graph.
	 * 
	 * @return
	 */
	public String rootNodePage() {
		StringBuffer sb = new StringBuffer();

		/* The search form */
		sb.append(searchForm());

		/* The table header */
		sb.append("<table width=\"100%\">");
		sb.append("<tr>");
		sb.append("<td><center>The Root Node:</center></td>");
		sb.append("<td><center>Entry points:</center></td>");
		sb.append("</tr>");

		/* Add the root node */
		sb.append("<tr>");
		sb.append("<td width=\"50%\" valign=\"top\">");
		sb.append(node("root"));
		sb.append("</td>");
		
		/* Add the entry points */
		sb.append("<td width=\"50%\" valign=\"top\">");
		for (ProbeMethod method : supergraph.entryPoints()) {
			sb.append(node(method));
		}
		sb.append("</td>");
		
		/* Close up the table */
		sb.append("</tr>");
		sb.append("</table>\n");
		return html(sb.toString());
	}

	/**
	 * Create the HTML page for a class.
	 * 
	 * @param cls
	 * @return
	 */
	public String nodePage(ProbeClass cls) {
		StringBuffer sb = new StringBuffer();
		String nodeid = (String) classIdMap.get(cls);
		sb.append(searchForm());
		sb.append(node(nodeid));
		for (ProbeMethod method : methodIdMap.keySet()) {
			if (method.cls().equals(cls))
				sb.append(node(method));
		}
		return html(sb.toString());
	}

	/**
	 * Create the HTML page for a method.
	 * 
	 * @param m
	 * @return
	 */
	public String nodePage(ProbeMethod m) {
		StringBuffer sb = new StringBuffer();
		String nodeid = (String) methodIdMap.get(m);

		/* The search form */
		sb.append(searchForm());

		/* The method name in the middle */
		sb.append("<center>");
		sb.append(node(nodeid));
		sb.append("</center>");

		/* The table header */
		sb.append("<table width=\"100%\">");
		sb.append("<tr>");
		sb.append("<td><center>Incoming edges:</center></td>");
		sb.append("<td><center>Outgoing edges:</center></td>");
		sb.append("</tr>");

		/* The incoming edges */
		sb.append("<tr>");
		sb.append("<td width=\"50%\" valign=\"top\">");

		// Add the root node if the method is an entry point
		if (supergraph.entryPoints().contains(m)) {
			sb.append(node("root"));
		}

		// Add the other incoming nodes
		for (probe.CallEdge edge : supergraph.edges()) {
			if (edge.dst().equals(m)) {
				sb.append(node(edge.src()));
			}
		}
		sb.append("</td>");

		/* Outgoing edges */
		sb.append("<td width=\"50%\" valign=\"top\">");
		for (probe.CallEdge edge : supergraph.edges()) {
			if (edge.src().equals(m)) {
				sb.append(node(edge.dst(), ((CallEdge) edge).context()));
			}
		}
		sb.append("</td>");

		/* Close up the table */
		sb.append("</tr>");
		sb.append("</table>\n");
		return html(sb.toString());
	}

	/**
	 * Get the right HTML node for the given nodeid.
	 * 
	 * @param nodeid
	 * @return
	 */
	public String node(String nodeid) {
		if (nodeid.equalsIgnoreCase("root")) {
			return rootNode();
		} else if (nodeid.startsWith("c")) {
			return node((ProbeClass) idClassMap.get(nodeid));
		} else if (nodeid.startsWith("m")) {
			return node((ProbeMethod) idMethodMap.get(nodeid));
		} else {
			throw new RuntimeException("bad node id");
		}
	}

	/**
	 * Create an HTML node for the calling context.
	 * 
	 * @param context
	 * @return
	 */
	public String node(CallingContext context) {
		StringBuffer sb = new StringBuffer();

		sb.append("<table>");
		sb.append("<tr>");
		sb.append("<td align=\"center\" bgcolor=\"lightblue\">");
		sb.append(context);
		sb.append("</td>");
		sb.append("</tr>");
		sb.append("</table>");

		return sb.toString();
	}

	/**
	 * Create an HTML node for the root node.
	 * 
	 * @return
	 */
	public String rootNode() {
		StringBuffer sb = new StringBuffer();

		String nodeid = "root";
		sb.append("<table>");
		sb.append("<tr>");
		sb.append("<td bgcolor=\"lightgreen\">");
		sb.append(link("node", nodeid, "ROOT"));
		sb.append("</td></tr></table>");

		return sb.toString();
	}

	/**
	 * Create an HTML node for a method.
	 * 
	 * @param m
	 * @return
	 */
	public String node(ProbeMethod m) {
		StringBuffer sb = new StringBuffer();
		String nodeid = (String) methodIdMap.get(m);
		sb.append("<table><tr>");
		if (subgraphReachables != null && subgraphReachables.contains(m)) {
			sb.append("<td bgcolor=\"pink\">");
		} else {
			sb.append("<td bgcolor=\"lightblue\">");
		}
		sb.append(node(m.cls()));
		sb.append(link("node", nodeid));
		sb.append(escape(m.name()));
		sb.append("(");
		sb.append(escape(m.signature()));
		sb.append(")");
		sb.append("</a></td></tr></table>");
		return sb.toString();
	}
	
	/**
	 * Create an HTML node for a method.
	 * 
	 * @param m
	 * @param context
	 * @return
	 */
	public String node(ProbeMethod m, CallingContext context) {
		StringBuffer sb = new StringBuffer();
		String nodeid = (String) methodIdMap.get(m);
		
		sb.append("<table>");
		sb.append("<tr>");
		if (subgraphReachables != null && subgraphReachables.contains(m)) {
			sb.append("<td bgcolor=\"pink\">");
		} else {
			sb.append("<td bgcolor=\"lightblue\">");
		}
		
		/* The class name */
		sb.append(node(m.cls()));
		
		/* The method signature */
		sb.append(link("node", nodeid, escape(m.name()) + "(" + escape(m.signature()) + ")"));
		
		/* The calling context */
		sb.append(node(context));
		
		/* Close up the table */
		sb.append("</td>");
		sb.append("</tr>");
		sb.append("</table>");
		return sb.toString();
	}

	/**
	 * Create an HTML node for a class.
	 * 
	 * @param cls
	 * @return
	 */
	public String node(ProbeClass cls) {
		StringBuffer sb = new StringBuffer();
		String nodeid = (String) classIdMap.get(cls);
		sb.append("<table><tr><td>");
		sb.append("<b>");
		sb.append(link("node", nodeid));
		sb.append(escape(cls.pkg()));
		sb.append(".");
		sb.append(escape(cls.name()));
		sb.append("</a>");
		sb.append("</b>");
		sb.append("</td></tr></table>");
		return sb.toString();
	}

	/**
	 * Create an HTML node for the search form.
	 * 
	 * @return
	 */
	public String search() {
		return html(searchForm());
	}

	/**
	 * Create an HTML link.
	 * 
	 * @param key
	 * @param value
	 * @param text
	 * @return
	 */
	public String link(String key, String value, String text) {
		return "<a href=" + url(key, value) + " style=\"text-decoration: none\">" + text + "</a>";
	}

	/**
	 * Search for a key.
	 * 
	 * @param key
	 * @return
	 */
	public String search(String key) {
		StringBuffer sb = new StringBuffer();
		sb.append(searchForm());
		sb.append("Search results for " + key);
		for (ProbeClass cls : classIdMap.keySet()) {
			if (matches(key, cls.pkg()) || matches(key, cls.name()))
				sb.append(node(cls));
		}
		for (ProbeMethod method : methodIdMap.keySet()) {
			if (matches(key, method.name()))
				sb.append(node(method));
		}
		return html(sb.toString());
	}

	/**
	 * Match a search term with some data.
	 * 
	 * @param needle
	 * @param haystack
	 * @return
	 */
	public boolean matches(String needle, String haystack) {
		return haystack.indexOf(needle) >= 0;
	}

	/**
	 * The HTML code for the search form.
	 * 
	 * @return
	 */
	public String searchForm() {
		return "" + "<form>" + "<table><tr><td>" + "Search for: " + "<input type=\"text\" name=\"search\"></td>"
				+ "<td bgcolor=\"pink\">" + supergraphName + " /\\ " + subgraphName + "</td>"
				+ "<td bgcolor=\"lightblue\">" + supergraphName + " - " + subgraphName + "</td>" + "</tr></table>"
				+ "</form>" + "<hr>";
	}

	/**
	 * Add a method from the call graph.
	 * 
	 * @param m
	 */
	private void addMethod(ProbeMethod m) {
		if (!methodIdMap.containsKey(m)) {
			addClass(m.cls());
			String id = "m" + nextId++;
			methodIdMap.put(m, id);
			idMethodMap.put(id, m);
		}
	}

	/**
	 * Add a class from the call graph.
	 * 
	 * @param cls
	 */
	private void addClass(ProbeClass cls) {
		if (!classIdMap.containsKey(cls)) {
			String id = "c" + nextId++;
			classIdMap.put(cls, id);
			idClassMap.put(id, cls);
		}
	}

	/**
	 * Read the call graph from the given file.
	 * 
	 * @param filename
	 * @return
	 */
	private static CallGraph readCallGraph(String filename) {
		CallGraph ret;
		try {
			try {
				ret = new GXLReader().readCallGraph(new FileInputStream(filename));
			} catch (RuntimeException e) {
				ret = new GXLReader().readCallGraph(new GZIPInputStream(new FileInputStream(filename)));
			}
		} catch (IOException e) {
			throw new RuntimeException("caught IOException " + e);
		}
		return ret;
	}
}