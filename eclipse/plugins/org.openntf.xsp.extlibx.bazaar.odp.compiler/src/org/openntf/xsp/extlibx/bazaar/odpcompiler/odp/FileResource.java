package org.openntf.xsp.extlibx.bazaar.odpcompiler.odp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import org.openntf.xsp.extlibx.bazaar.odpcompiler.util.DXLUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.ibm.commons.util.StringUtil;
import com.ibm.commons.xml.DOMUtil;
import com.ibm.commons.xml.XMLException;

/**
 * Represents a "file resource"-type element in the ODP, which may be a file resource,
 * stylesheet, or other loose file.
 * 
 * @author Jesse Gallagher
 * @since 2.0.0
 */
public class FileResource extends AbstractSplitDesignElement {
	private final String flags;
	private final String flagsExt;
	private final Function<Path, String> nameProvider;
	private final boolean copyToClasses;
	
	public FileResource(Path dataFile) {
		this(dataFile, null, null, null);
	}
	
	public FileResource(Path dataFile, boolean copyToClasses) {
		super(dataFile);
		this.flags = null;
		this.flagsExt = null;
		this.nameProvider = null;
		this.copyToClasses = copyToClasses;
	}
	
	public FileResource(Path dataFile, String flags, String flagsExt, Function<Path, String> nameProvider) {
		super(dataFile);
		this.flags = flags;
		this.flagsExt = flagsExt;
		this.nameProvider = nameProvider;
		this.copyToClasses = false;
	}
	
	@Override
	public Document getDxl() throws XMLException, IOException {
		if(Files.isRegularFile(getDxlFile())) {
			return super.getDxl();
		} else {
			if(nameProvider == null) {
				throw new IllegalStateException("No name provider provided for " + getDataFile());
			}
			
			Document dxlDoc = DOMUtil.createDocument();
			Element note = DOMUtil.createElement(dxlDoc, "note");
			note.setAttribute("class", "form");
			note.setAttribute("xmlns", "http://www.lotus.com/dxl");
			if(StringUtil.isNotEmpty(flags)) {
				DXLUtil.writeItemString(dxlDoc, "$Flags", false, flags);
			}
			if(StringUtil.isNotEmpty(flagsExt)) {
				DXLUtil.writeItemString(dxlDoc, "$FlagsExt", false, flagsExt);
			}
			String title = nameProvider.apply(getDataFile());
			if(StringUtil.isNotEmpty(title)) {
				DXLUtil.writeItemString(dxlDoc, "$TITLE", false, title);
				DXLUtil.writeItemString(dxlDoc, "$FileNames", false, title);
			}
			
			return attachFileData(dxlDoc);
		}		
	}
	
	public boolean isCopyToClasses() {
		return copyToClasses;
	}
}