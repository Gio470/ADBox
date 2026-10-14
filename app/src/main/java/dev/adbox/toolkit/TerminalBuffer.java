package dev.adbox.toolkit;

import java.util.*;

/** A compact VT100 screen: cursor, erase, scrolling, alternate screen and OSC. */
public final class TerminalBuffer {
    private final int rows,cols;private char[][] cells,normal;private int row,col,savedRow,savedCol,state;private StringBuilder escape=new StringBuilder();
    private final Deque<String> history=new ArrayDeque<>();
    public TerminalBuffer(int rows,int cols){this.rows=rows;this.cols=cols;cells=blank();}
    private char[][] blank(){char[][] b=new char[rows][cols];for(char[] line:b)Arrays.fill(line,' ');return b;}
    public synchronized void feed(String text){for(char c:text.toCharArray())accept(c);}
    private void accept(char c){
        if(state==3){if(c==7){state=0;}else if(c==27)state=4;return;}
        if(state==4){state=c=='\\'?0:3;return;}
        if(state==1){state=0;if(c=='['){state=2;escape.setLength(0);}else if(c==']')state=3;else if(c=='7'){savedRow=row;savedCol=col;}else if(c=='8'){row=savedRow;col=savedCol;}else if(c=='c'){cells=blank();row=col=0;}return;}
        if(state==2){if(c>=0x40&&c<=0x7e){control(c,escape.toString());state=0;}else if(escape.length()<128)escape.append(c);else state=0;return;}
        if(c==27){state=1;return;}if(c=='\r'){col=0;return;}if(c=='\n'){newline();return;}if(c=='\b'){col=Math.max(0,col-1);return;}if(c=='\t'){col=Math.min(cols-1,(col/8+1)*8);return;}if(c<32||c==127)return;
        if(col>=cols){col=0;newline();}cells[row][col++]=c;
    }
    private void newline(){if(++row>=rows){if(normal==null){history.addLast(new String(cells[0]));while(history.size()>500)history.removeFirst();}System.arraycopy(cells,1,cells,0,rows-1);cells[rows-1]=new char[cols];Arrays.fill(cells[rows-1],' ');row=rows-1;}}
    private void control(char code,String raw){
        String[] args=raw.replace("?","").split(";",-1);int n=value(args,0,1);
        switch(code){case 'A':row=Math.max(0,row-n);break;case 'B':row=Math.min(rows-1,row+n);break;case 'C':col=Math.min(cols-1,col+n);break;case 'D':col=Math.max(0,col-n);break;case 'E':row=Math.min(rows-1,row+n);col=0;break;case 'F':row=Math.max(0,row-n);col=0;break;case 'G':col=Math.min(cols-1,n-1);break;case 'd':row=Math.min(rows-1,n-1);break;case 'H':case 'f':row=Math.min(rows-1,n-1);col=Math.min(cols-1,value(args,1,1)-1);break;case 'J':int mode=value(args,0,0);if(mode==2||mode==3){cells=blank();if(mode==3)history.clear();}else if(mode==0){Arrays.fill(cells[row],Math.min(col,cols),cols,' ');for(int r=row+1;r<rows;r++)Arrays.fill(cells[r],' ');}else if(mode==1){for(int r=0;r<row;r++)Arrays.fill(cells[r],' ');Arrays.fill(cells[row],0,Math.min(col+1,cols),' ');}break;case 'K':int k=value(args,0,0);Arrays.fill(cells[row],k==0?Math.min(col,cols):0,k==1?Math.min(cols,col+1):cols,' ');break;case 's':savedRow=row;savedCol=col;break;case 'u':row=savedRow;col=savedCol;break;case 'h':case 'l':if(raw.equals("?1049")){if(code=='h'&&normal==null){normal=cells;cells=blank();savedRow=row;savedCol=col;row=col=0;}else if(code=='l'&&normal!=null){cells=normal;normal=null;row=savedRow;col=savedCol;}}break;default:break;}
    }
    private int value(String[] args,int index,int fallback){try{int v=Integer.parseInt(args[index]);return v==0&&fallback==1?1:Math.max(0,v);}catch(Exception e){return fallback;}}
    public synchronized String snapshot(){StringBuilder b=new StringBuilder();if(normal==null)for(String s:history)b.append(s).append('\n');for(int r=0;r<rows;r++){b.append(cells[r]);if(r<rows-1)b.append('\n');}return b.toString();}
}
