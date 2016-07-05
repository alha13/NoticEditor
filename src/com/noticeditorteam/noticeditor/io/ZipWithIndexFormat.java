package com.noticeditorteam.noticeditor.io;

import com.noticeditorteam.noticeditor.model.*;
import java.io.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import javafx.scene.control.TreeItem;
import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Zip4jConstants;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Document format that stores to zip archive with index.json.
 *
 * @author aNNiMON
 */
public class ZipWithIndexFormat {

	private static final String INDEX_JSON = "index.json";

	private static final String BRANCH_PREFIX = "branch_";
	private static final String NOTE_PREFIX = "note_";

	public static ZipWithIndexFormat with(File file) throws ZipException {
		return new ZipWithIndexFormat(file);
	}

	private final Set<String> paths;
	private final ZipFile zip;
	private final ZipParameters parameters;

	private ZipWithIndexFormat(File file) throws ZipException {
		paths = new HashSet<>();
		zip = new ZipFile(file);
		parameters = new ZipParameters();
	}

	public NoticeTree importDocument() throws IOException, JSONException, ZipException {
		String indexContent = readFile(INDEX_JSON);
		if (indexContent.isEmpty()) {
			throw new IOException("Invalid file format");
		}

		JSONObject index = new JSONObject(indexContent);
		return new NoticeTree(readNotices("", index));
	}

	private String readFile(String path) throws IOException, ZipException {
		FileHeader header = zip.getFileHeader(path);
		if (header == null)
			return "";
		return IOUtil.stringFromStream(zip.getInputStream(header));
	}

    private byte[] readBytes(String path) throws IOException, ZipException {
		FileHeader header = zip.getFileHeader(path);
		if (header == null) return new byte[0];
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        IOUtil.copy(zip.getInputStream(header), baos);
        baos.flush();
		return baos.toByteArray();
	}

	private NoticeTreeItem readNotices(String dir, JSONObject index) throws IOException, JSONException, ZipException {
		final String title = index.getString(JsonFields.KEY_TITLE);
		final String filename = index.getString(JsonFields.KEY_FILENAME);
		final int status = index.optInt(JsonFields.KEY_STATUS, NoticeItem.STATUS_NORMAL);
		final String dirPrefix = index.has(JsonFields.KEY_CHILDREN) ? BRANCH_PREFIX : NOTE_PREFIX;

		final String newDir = dir + dirPrefix + filename + "/";
		if (index.has(JsonFields.KEY_CHILDREN)) {
			JSONArray children = index.getJSONArray(JsonFields.KEY_CHILDREN);
			NoticeTreeItem branch = new NoticeTreeItem(title);
			for (int i = 0; i < children.length(); i++) {
				branch.addChild(readNotices(newDir, children.getJSONObject(i)));
			}
			return branch;
		} else {
			// ../note_filename/filename.md
			final String mdPath = newDir + filename + ".md";
            final NoticeTreeItem item = new NoticeTreeItem(title, readFile(mdPath), status);
            if (index.has(JsonFields.KEY_ATTACHMENTS)) {
                Attachments attachments = readAttachments(newDir, index.getJSONArray(JsonFields.KEY_ATTACHMENTS));
                item.setAttachments(attachments);
            }
			return item;
		}
	}

    private Attachments readAttachments(String newDir, JSONArray jsonAttachments) throws IOException, JSONException, ZipException {
        Attachments attachments = new Attachments();
        final int length = jsonAttachments.length();
        for (int i = 0; i < length; i++) {
            JSONObject jsonAttachment = jsonAttachments.getJSONObject(i);
            final String name = jsonAttachment.getString(JsonFields.KEY_ATTACHMENT_NAME);
            Attachment attachment = new Attachment(name, readBytes(newDir + name));
            attachments.add(attachment);
        }
        return attachments;
    }

	public void export(NoticeTreeItem notice) throws IOException, JSONException, ZipException {
		parameters.setCompressionMethod(Zip4jConstants.COMP_DEFLATE);
		parameters.setCompressionLevel(Zip4jConstants.DEFLATE_LEVEL_NORMAL);
		parameters.setSourceExternalStream(true);

		JSONObject index = new JSONObject();
		writeNoticesAndFillIndex("", notice, index);
		storeFile(INDEX_JSON, index.toString());
	}

	public void export(NoticeTree tree) throws IOException, JSONException, ZipException {
		export(tree.getRoot());
	}

	private void storeFile(String path, String content) throws IOException, ZipException {
		parameters.setFileNameInZip(path);
		try (InputStream stream = IOUtil.toStream(content)) {
			zip.addStream(stream, parameters);
		}
	}

    private void storeFile(String path, byte[] data) throws IOException, ZipException {
		parameters.setFileNameInZip(path);
		try (InputStream stream = new ByteArrayInputStream(data)) {
			zip.addStream(stream, parameters);
		}
	}

	private void writeNoticesAndFillIndex(String dir, NoticeTreeItem item, JSONObject index) throws IOException, JSONException, ZipException {
		final String title = item.getTitle();
		final String dirPrefix = item.isBranch() ? BRANCH_PREFIX : NOTE_PREFIX;
		String filename = IOUtil.sanitizeFilename(title);

		String newDir = dir + dirPrefix + filename;
		if (paths.contains(newDir)) {
			// solve collision
			int counter = 1;
			String newFileName = filename;
			while (paths.contains(newDir)) {
				newFileName = String.format("%s_(%d)", filename, counter++);
				newDir = dir + dirPrefix + newFileName;
			}
			filename = newFileName;
		}
		paths.add(newDir);

		index.put(JsonFields.KEY_TITLE, title);
		index.put(JsonFields.KEY_FILENAME, filename);

		if (item.isBranch()) {
			// ../branch_filename
			ArrayList list = new ArrayList();
			for (TreeItem<NoticeItem> object : item.getInternalChildren()) {
				NoticeTreeItem child = (NoticeTreeItem) object;

				JSONObject indexEntry = new JSONObject();
				writeNoticesAndFillIndex(newDir + "/", child, indexEntry);
				list.add(indexEntry);
			}
			index.put(JsonFields.KEY_CHILDREN, new JSONArray(list));
		} else {
			// ../note_filename/filename.md
			index.put(JsonFields.KEY_STATUS, item.getStatus());
			storeFile(newDir + "/" + filename + ".md", item.getContent());
            writeAttachments(newDir, item.getAttachments(), index);
		}
	}

    private void writeAttachments(String newDir, Attachments attachments, JSONObject index) throws JSONException, IOException, ZipException {
        // Store filenames in index.json and content in file.
        final JSONArray jsonAttachments = new JSONArray();
        for (Attachment attachment : attachments) {
            final JSONObject jsonAttachment = new JSONObject();
            jsonAttachment.put(JsonFields.KEY_ATTACHMENT_NAME, attachment.getName());
            jsonAttachments.put(jsonAttachment);
            storeFile(newDir + "/" + attachment.getName(), attachment.getData());
        }
        index.put(JsonFields.KEY_ATTACHMENTS, jsonAttachments);
    }
}
