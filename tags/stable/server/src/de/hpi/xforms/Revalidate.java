package de.hpi.xforms;

public class Revalidate extends AbstractAction {

	public Revalidate() {
		super();
		attributes.put("model", null);
	}
	
	@Override
	public String getTagName() {
		return "revalidate";
	}

}
