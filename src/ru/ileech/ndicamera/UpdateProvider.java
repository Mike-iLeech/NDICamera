package ru.ileech.ndicamera;
import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
public class UpdateProvider extends ContentProvider {
 @Override public boolean onCreate(){return true;}
 private void check(Uri uri){if(!"/apk".equals(uri.getPath()))throw new IllegalArgumentException("Unknown update URI");}
 @Override public String getType(Uri uri){check(uri);return "application/vnd.android.package-archive";}
 @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{check(uri);if(!"r".equals(mode))throw new FileNotFoundException("Read only");return ParcelFileDescriptor.open(Updates.apk(getContext()),ParcelFileDescriptor.MODE_READ_ONLY);}
 @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){check(uri);String[] columns=projection!=null?projection:new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE};MatrixCursor cursor=new MatrixCursor(columns);Object[] row=new Object[columns.length];for(int i=0;i<columns.length;i++)if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))row[i]="NDICamera-update.apk";else if(OpenableColumns.SIZE.equals(columns[i]))row[i]=Updates.apk(getContext()).length();cursor.addRow(row);return cursor;}
 @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
 @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
 @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
