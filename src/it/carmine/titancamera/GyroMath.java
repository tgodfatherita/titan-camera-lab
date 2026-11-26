package it.carmine.titancamera;

import java.io.*;
import java.util.*;

/** Pure Java port of the user-selected gyro-experiment/render_gyro.py geometry. */
public final class GyroMath {
    public static final double OFFSET=.024, ZOOM=1.10;
    public final double[][] corners;
    public final double strength;
    final float[] inverseRotation;
    final float focalPixels;
    interface Samples { boolean next(double[] destination) throws IOException; }

    public GyroMath(double[][] samples,double[] times,double focal,int width,int height) throws IOException {
        this(new Samples() {
            int index;
            public boolean next(double[] destination) {
                if (index == samples.length) return false;
                System.arraycopy(samples[index++], 0, destination, 0, 4);
                return true;
            }
        }, times, focal, width, height);
    }

    private static double[][] framePoses(Samples input, double[] times) throws IOException {
        double[] left = new double[4], right = new double[4], step = new double[3];
        if (!input.next(right) || right[0] > times[0] + OFFSET) throw new IOException("Inizio dei dati giroscopio mancante");
        double[] previous = {0,0,0,1}, current = previous;
        double[][] poses = new double[times.length][4];
        boolean haveInterval = false;
        for (int i = 0; i < times.length; i++) {
            double target = times[i] + OFFSET;
            while (!haveInterval || right[0] < target) {
                double[] swap = left; left = right; right = swap;
                if (!input.next(right)) throw new IOException("Dati movimento incompleti. Prova Marcata.");
                double dt = right[0] - left[0];
                if (!(dt > 0 && dt <= .05)) throw new IOException("Interruzione nei dati giroscopio. Prova Marcata.");
                for (int axis = 0; axis < 3; axis++) {
                    step[axis] = (left[axis+1] + right[axis+1]) * .5 * dt * (axis == 0 ? -1 : 1);
                    if (!Double.isFinite(step[axis])) throw new IOException("Dati giroscopio non validi");
                }
                previous = current;
                current = norm(mul(exp(step), previous));
                haveInterval = true;
            }
            double fraction = (target - left[0]) / (right[0] - left[0]);
            for (int k = 0; k < 4; k++) poses[i][k] = previous[k]*(1-fraction) + current[k]*fraction;
            norm(poses[i]);
        }
        return poses;
    }

    GyroMath(Samples samples,double[] times,double focal,int width,int height) throws IOException {
        focalPixels = (float) focal;
        double[][] fq = framePoses(samples, times);
        double[][] rotations=new double[times.length][3];
        double sigma=6; // Exact 30 fps reference: Gaussian sigma 6, radius 24, nearest edges.
        double[] weights = new double[49];
        for (int d=-24;d<=24;d++) weights[d+24] = Math.exp(-.5*d*d/(sigma*sigma));
        for(int i=0;i<times.length;i++){
            double[] smooth=new double[4];
            for(int d=-24;d<=24;d++){double weight=weights[d+24];int n=Math.max(0,Math.min(times.length-1,i+d));for(int k=0;k<4;k++)smooth[k]+=weight*fq[n][k];}
            rotations[i]=log(norm(mul(norm(smooth),conjugate(fq[i]))));
        }
        double lo=0,hi=1;
        if (valid(rotations,1,focal,width,height)) lo = 1 - Math.scalb(1.,-22);
        else for(int n=0;n<22;n++){double mid=(lo+hi)/2;if(valid(rotations,mid,focal,width,height))lo=mid;else hi=mid;}
        strength=lo;corners=new double[times.length][8];
        inverseRotation = new float[times.length*9];
        for(int i=0;i<times.length;i++){
            double[] r=scaled(rotations[i],lo);double[][] m=matrix(exp(r));
            // Column-major GL inverse is the row-major forward rotation (orthonormal).
            for (int row=0;row<3;row++) for (int col=0;col<3;col++)
                inverseRotation[i*9+row*3+col] = (float)m[row][col];
            // FFmpeg perspective uses corners (0,0),(W,0),(0,H),(W,H), not W-1/H-1.
            for(int c=0;c<4;c++){double[] p=project(m,(c%2)*width,(c/2)*height,focal,width,height,false);corners[i][2*c]=p[0];corners[i][2*c+1]=p[1];}
        }
    }
    private static boolean valid(double[][] rotations,double strength,double f,int w,int h){
        for(double[] r:rotations){double[][] m=matrix(conjugate(exp(scaled(r,strength))));
            for(int c=0;c<4;c++){double[] p=project(m,(c%2)*(w-1),(c/2)*(h-1),f,w,h,true);if(!Double.isFinite(p[0])||!Double.isFinite(p[1])||p[0]<1||p[0]>=w-2||p[1]<1||p[1]>=h-2)return false;}}
        return true;
    }
    private static double[] project(double[][] m,double x,double y,double f,int w,int h,boolean inverse){
        if(inverse){x=(x-w/2.)/ZOOM+w/2.;y=(y-h/2.)/ZOOM+h/2.;}
        double[] v={(x-w/2.)/f,(y-h/2.)/f,1},p=new double[3];for(int a=0;a<3;a++)for(int b=0;b<3;b++)p[a]+=m[a][b]*v[b];
        if(p[2]<=0)return new double[]{Double.NaN,Double.NaN};
        x=f*p[0]/p[2]+w/2.;y=f*p[1]/p[2]+h/2.;
        if(!inverse){x=(x-w/2.)*ZOOM+w/2.;y=(y-h/2.)*ZOOM+h/2.;}return new double[]{x,y};
    }
    public void writeFilter(File file)throws IOException{
        try(Writer out=new BufferedWriter(new FileWriter(file))){out.write("perspective=eval=frame:sense=destination:interpolation=cubic");
            for(int k=0;k<8;k++){out.write(":"+(k%2==0?"x":"y")+(k/2)+"='");expression(out,k,0,corners.length);out.write("'");}}
    }
    private void expression(Writer out,int k,int lo,int hi)throws IOException{
        if(hi-lo==1){out.write(String.format(Locale.ROOT,"%.6f",corners[lo][k]));return;}
        int mid=(lo+hi)/2;out.write("if(lt(in,"+(mid+1)+"),");expression(out,k,lo,mid);out.write(",");expression(out,k,mid,hi);out.write(")");
    }
    private static double[] scaled(double[] a,double v){return new double[]{a[0]*v,a[1]*v,a[2]*v};}
    private static double[] norm(double[] q){double n=0;for(double v:q)n+=v*v;n=Math.sqrt(n);for(int i=0;i<q.length;i++)q[i]/=n;return q;}
    private static double[] conjugate(double[] q){return new double[]{-q[0],-q[1],-q[2],q[3]};}
    private static double[] exp(double[] v){double a=Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]),s=a<1e-12?.5:Math.sin(a/2)/a;return new double[]{v[0]*s,v[1]*s,v[2]*s,Math.cos(a/2)};}
    private static double[] log(double[] q){if(q[3]<0)for(int i=0;i<4;i++)q[i]=-q[i];double n=Math.sqrt(q[0]*q[0]+q[1]*q[1]+q[2]*q[2]),s=n<1e-12?2:2*Math.atan2(n,q[3])/n;return new double[]{q[0]*s,q[1]*s,q[2]*s};}
    private static double[] mul(double[] a,double[] b){return new double[]{a[3]*b[0]+a[0]*b[3]+a[1]*b[2]-a[2]*b[1],a[3]*b[1]-a[0]*b[2]+a[1]*b[3]+a[2]*b[0],a[3]*b[2]+a[0]*b[1]-a[1]*b[0]+a[2]*b[3],a[3]*b[3]-a[0]*b[0]-a[1]*b[1]-a[2]*b[2]};}
    private static double[][] matrix(double[] q){double x=q[0],y=q[1],z=q[2],w=q[3];return new double[][]{{1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w)},{2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w)},{2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y)}};}
}
