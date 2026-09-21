package jp.cssj.homare.impl.formatter.document;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import jp.cssj.cti2.TranscoderException;
import jp.cssj.cti2.helpers.CTIMessageCodes;
import jp.cssj.homare.formatter.Formatter;
import jp.cssj.homare.message.MessageCodeUtils;
import jp.cssj.homare.message.MessageCodes;
import jp.cssj.homare.ua.AbortException;
import jp.cssj.homare.ua.UserAgent;
import jp.cssj.homare.xml.Parser;
import jp.cssj.homare.xml.ParserFactory;
import jp.cssj.homare.xml.XMLHandler;
import jp.cssj.plugin.PluginLoader;
import jp.cssj.resolver.Source;

import org.xml.sax.SAXException;

public class DocumentFormatter implements Formatter {
	private static final Logger LOG = Logger.getLogger(DocumentFormatter.class.getName());

	public boolean match(Source key) {
		return true;
	}

	public void format(Source source, UserAgent ua) throws AbortException, TranscoderException {
		try {
			String mimeType = source.getMimeType();
			ParserFactory pf = (ParserFactory) PluginLoader.getPluginLoader().search(ParserFactory.class, mimeType);
			Parser parser = pf.createParser();
			XMLHandler entryPoint = new TranscoderHandler(ua);
			parser.parse(ua, source, entryPoint);
		} catch (IOException e) {
			// 型のついた失敗(TranscoderException)は包み直さない。包むと
			// 「I/O error. I/O error. ...」と前置きが二重になり、元の符号も失われる
			// (2026-09-21 に copper 4 から移植)。
			if (e instanceof TranscoderException) {
				throw (TranscoderException) e;
			}
			short code = CTIMessageCodes.ERROR_IO;
			String[] args = new String[] { e.getMessage() };
			String mes = MessageCodeUtils.toString(code, args);
			ua.message(code, args);
			LOG.log(Level.WARNING, mes, e);
			throw new TranscoderException(code, args, mes);
		} catch (SAXException e) {
			short code = MessageCodes.ERROR_BAD_XML_SYNTAX;
			String[] args = new String[] { e.getMessage() };
			String mes = MessageCodeUtils.toString(code, args);
			ua.message(code, args);
			LOG.log(Level.WARNING, mes, e);
			throw new TranscoderException(code, args, mes);
		}
	}
}
