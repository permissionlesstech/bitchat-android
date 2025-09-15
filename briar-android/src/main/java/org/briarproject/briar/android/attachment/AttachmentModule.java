package org.briarproject.briar.android.attachment;

import android.app.Application;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

import static org.briarproject.briar.android.attachment.AttachmentDimensions.getAttachmentDimensions;

@Module
public class AttachmentModule {

	@Provides
	AttachmentDimensions provideAttachmentDimensions(Application app) {
		return getAttachmentDimensions(app.getResources());
	}

	@Provides
	@Singleton
	AttachmentRetriever provideAttachmentRetriever(
			AttachmentRetrieverImpl attachmentRetriever) {
		return attachmentRetriever;
	}

	@Provides
	@Singleton
	AttachmentCreator provideAttachmentCreator(
			AttachmentCreatorImpl attachmentCreator) {
		return attachmentCreator;
	}
}
