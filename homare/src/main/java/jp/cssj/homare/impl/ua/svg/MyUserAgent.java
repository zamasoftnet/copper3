package jp.cssj.homare.impl.ua.svg;

import java.awt.geom.Dimension2D;

import org.apache.batik.bridge.ExternalResourceSecurity;
import org.apache.batik.bridge.NoLoadScriptSecurity;
import org.apache.batik.bridge.RelaxedExternalResourceSecurity;
import org.apache.batik.bridge.ScriptSecurity;
import org.apache.batik.util.ParsedURL;

import jp.cssj.homare.message.MessageCodes;
import jp.cssj.homare.ua.UserAgent;
import jp.cssj.sakae.svg.UserAgentImpl;

class MyUserAgent extends UserAgentImpl {
	protected final String docURI;
	protected final UserAgent ua;

	public MyUserAgent(String docURI, UserAgent ua, Dimension2D viewport) {
		super(viewport);
		this.docURI = docURI;
		this.ua = ua;
		this.addStdFeatures();
	}

	public Dimension2D getViewportSize() {
		return this.viewport;
	}

	public void displayError(String message) {
		this.ua.message(MessageCodes.WARN_SVG, this.docURI, message);
	}

	public void displayError(Exception e) {
		this.ua.message(MessageCodes.WARN_SVG, this.docURI, e.getMessage());
	}

	public void displayMessage(String message) {
		this.ua.message(MessageCodes.WARN_SVG, this.docURI, message);
	}

	public float getPixelUnitToMillimeter() {
		return (float) (25.4 / this.ua.getPixelsPerInch());
	}

	public String getLanguages() {
		return this.ua.getDefaultLocale().getLanguage();
	}

	public String getMedia() {
		return "print";
	}

	public String getDefaultFontFamily() {
		return this.ua.getDefaultFontFamily().get(0).getName();
	}

	public ScriptSecurity getScriptSecurity(String scriptType, ParsedURL scriptPURL, ParsedURL docPURL) {
		return new NoLoadScriptSecurity(scriptType);
	}

	/**
	 * SVG が参照する外部資源(画像・別文書)を文書の場所で制限しない。
	 * <p>
	 * Batik 1.14 の既定は全許可だったが、1.19 は文書と同じ場所の資源しか読まない。
	 * そのままでは tmp:// で送った資源や別ホストの画像が読めず、SVG ごと落ちる。
	 * 取得は MySVGImageElementBridge と MyURIResolver から Copper の解決器
	 * ({@link UserAgent#resolve})を通るので、アクセスの制限はそちらで掛かる。1.14 と同じ動作に戻す。
	 * </p>
	 */
	public ExternalResourceSecurity getExternalResourceSecurity(ParsedURL resourceURL, ParsedURL docURL) {
		return new RelaxedExternalResourceSecurity(resourceURL, docURL);
	}
}
