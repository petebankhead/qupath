package qupath.lib.scripting;

import java.io.File;

/**
 * Information about the currently running script.
 * 
 * @author Pete Bankhead
 * @since v0.4.0
 */
public class ScriptInfo {
	
	private long startTime;
	
	private File file;
	private String script;
	
	private int batchSize;
	private int batchIndex;
	
	private ScriptInfo() {
		this.startTime = System.currentTimeMillis();
	}
	
	private ScriptInfo(ScriptInfo info) {
		this.startTime = info.startTime;
		this.file = info.file;
		this.script = info.script;
		this.batchSize = info.batchSize;
		this.batchIndex = info.batchIndex;
	}
	
	public File getFile() {
		return file;
	}
	
	public long getStartTime() {
		return startTime;
	}
	
	public String getScript() {
		return script;
	}
	
	public boolean isBatchScript() {
		return batchSize > 1;
	}
	
	public int getBatchSize() {
		return batchSize;
	}
	
	public int getBatchIndex() {
		return batchIndex;
	}
	
	public boolean isLastScript() {
		return batchIndex == batchSize - 1;
	}

	public boolean isFirstScript() {
		return batchIndex == 0;
	}
	
	
	public static Builder builder() {
		return new Builder();
	}
	
	public static class Builder {
		
		private ScriptInfo info = new ScriptInfo();
		
		public Builder file(File file) {
			info.file = file;
			return this;
		}
		
		public Builder script(String script) {
			info.script = script;
			return this;
		}
		
		public Builder startTime(long startTime) {
			info.startTime = startTime;
			return this;
		}
		
		public Builder batchSize(int batchSize) {
			info.batchSize = batchSize;
			return this;
		}
		
		public Builder batchIndex(int batchIndex) {
			info.batchIndex = batchIndex;
			return this;
		}
		
		public ScriptInfo build() {
			return new ScriptInfo(info);
		}
		
	}

}
