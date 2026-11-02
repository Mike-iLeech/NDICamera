package ru.ileech.ndicamera;
import android.app.job.*;
public class UpdateJob extends JobService {
 private Updates.Request request;
 @Override public boolean onStartJob(JobParameters p){request=Updates.check(this,false,()->jobFinished(p,false));return true;}
 @Override public boolean onStopJob(JobParameters p){if(request!=null)request.cancel();return Updates.prefs(this).getBoolean("autoUpdate",true);}
}
