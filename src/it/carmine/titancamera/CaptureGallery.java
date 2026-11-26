package it.carmine.titancamera;

import android.content.*;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Size;
import java.io.IOException;

/** The viewfinder opens the same published photo it uses for the thumbnail. */
final class CaptureGallery {
    static final Uri IMAGES=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    final Uri uri;
    final Bitmap thumbnail;
    CaptureGallery(Uri uri,Bitmap thumbnail){this.uri=uri;this.thumbnail=thumbnail;}

    static Uri latestPhoto(ContentResolver resolver,String lastPhotoName) {
        if(lastPhotoName!=null){
            Uri saved=findPhoto(resolver,lastPhotoName);
            if(saved!=null)return saved;
        }
        return findPhoto(resolver,null);
    }
    private static Uri findPhoto(ContentResolver resolver,String name) {
        String id=MediaStore.MediaColumns._ID;
        Bundle query=new Bundle();
        String album="DCIM/TitanCameraLab/";
        String selection=MediaStore.MediaColumns.RELATIVE_PATH+"=? AND "+MediaStore.MediaColumns.IS_PENDING+"=0";
        if(name!=null)selection+=" AND "+MediaStore.MediaColumns.DISPLAY_NAME+"=?";
        query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,selection);
        query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,name!=null?new String[]{album,name}:new String[]{album});
        query.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,MediaStore.MediaColumns.DATE_TAKEN+" DESC, "+id+" DESC");
        query.putInt(ContentResolver.QUERY_ARG_LIMIT,1);
        try(Cursor c=resolver.query(IMAGES,new String[]{id},query,null)) {
            return c!=null && c.moveToFirst()?ContentUris.withAppendedId(IMAGES,c.getLong(0)):null;
        }
    }
    static CaptureGallery load(ContentResolver resolver,Uri uri,CaptureGallery previous) {
        if(uri==null)return null;
        if(previous!=null && previous.uri.equals(uri) && previous.thumbnail!=null)return previous;
        Bitmap image=null;
        try {image=resolver.loadThumbnail(uri,new Size(144,144),null);}
        catch(IOException e){
            // A just-published JPEG can be readable before MediaStore has its thumbnail.
            try {
                image=ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver,uri),(decoder,info,source)->{
                    Size size=info.getSize();
                    decoder.setTargetSampleSize(Math.max(1,Math.max(size.getWidth(),size.getHeight())/144));
                });
            }catch(IOException ignored){}
        }
        return new CaptureGallery(uri,image);
    }
}
