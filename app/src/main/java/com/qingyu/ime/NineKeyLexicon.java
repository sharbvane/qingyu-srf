package com.qingyu.ime;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** A syllable graph of every source reading; edges accept full spelling or initials. */
final class NineKeyLexicon {
    private final String[] syllables,codes;
    private final int[] children,terminals,weights,rowids,frequencies;
    private final short[] labels;
    // ponytail: frequency-bounded 512 states and 128 complete matches per prefix; widen after measured common-word misses.
    private static final int BEAM=512;
    private NineKeyLexicon(String[] syllables,int[] children,int[] terminals,int[] weights,short[] labels,int[] rowids,int[] frequencies) {
        this.syllables=syllables;this.children=children;this.terminals=terminals;this.weights=weights;this.labels=labels;this.rowids=rowids;this.frequencies=frequencies;
        codes=new String[syllables.length];for(int i=0;i<codes.length;i++)codes[i]=digits(syllables[i]);
    }
    static NineKeyLexicon load(InputStream source)throws IOException {
        PushbackInputStream sniff=new PushbackInputStream(source,2);int first=sniff.read(),second=sniff.read();if(second>=0)sniff.unread(second);if(first>=0)sniff.unread(first);
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(first==0x1f&&second==0x8b?new GZIPInputStream(sniff):sniff,32768))) {
            if(in.readInt()!=0x51595439||in.readInt()!=1)throw new IOException("Unsupported T9 graph");
            int nodes=in.readInt(),rows=in.readInt(),count=in.readInt();
            if(nodes<1||nodes>1500000||rows<1||rows>1000000||count<1||count>1024)throw new IOException("Invalid T9 graph size");
            String[] syllables=new String[count];
            for(int i=0;i<count;i++){int size=in.readUnsignedShort();if(size<1||size>6)throw new IOException("Invalid T9 spelling");byte[] bytes=new byte[size];in.readFully(bytes);String spelling=new String(bytes,java.nio.charset.StandardCharsets.US_ASCII);if(!spelling.matches("[a-z]{1,6}")||i>0&&spelling.compareTo(syllables[i-1])<=0)throw new IOException("Invalid T9 spelling order");syllables[i]=spelling;}
            int[] children=new int[nodes+1],terminals=new int[nodes+1],weights=new int[nodes],rowids=new int[rows],frequencies=new int[rows];short[] labels=new short[nodes];
            byte[] buffer=new byte[32768];
            readInts(in,children,buffer);for(int i=0;i<=nodes;i++){if(children[i]<1||children[i]>nodes||i>0&&children[i]<children[i-1]||i<nodes&&children[i]<=i)throw new IOException("Invalid T9 child range");}
            if(children[0]!=1||children[nodes]!=nodes)throw new IOException("Invalid T9 root");
            readInts(in,terminals,buffer);for(int i=0;i<=nodes;i++){if(terminals[i]<0||terminals[i]>rows||i>0&&terminals[i]<terminals[i-1])throw new IOException("Invalid T9 terminal range");}
            if(terminals[0]!=0||terminals[nodes]!=rows)throw new IOException("Invalid T9 terminals");
            readInts(in,weights,buffer);for(int weight:weights)if(weight<1)throw new IOException("Invalid T9 frequency");
            for(int start=0;start<nodes;start+=buffer.length/2){int size=Math.min(buffer.length/2,nodes-start);in.readFully(buffer,0,size*2);java.nio.ByteBuffer.wrap(buffer,0,size*2).asShortBuffer().get(labels,start,size);}
            for(short label:labels)if(label<0||label>=count)throw new IOException("Invalid T9 label");
            readInts(in,rowids,buffer);for(int row:rowids)if(row<1||row>rows)throw new IOException("Invalid T9 dictionary row");
            readInts(in,frequencies,buffer);for(int frequency:frequencies)if(frequency<1)throw new IOException("Invalid T9 lexical frequency");
            for(int node=0;node<nodes;node++)for(int child=children[node]+1;child<children[node+1];child++)if(labels[child]<=labels[child-1])throw new IOException("Duplicate T9 child");
            if(in.read()!=-1)throw new IOException("Trailing T9 graph data");
            return new NineKeyLexicon(syllables,children,terminals,weights,labels,rowids,frequencies);
        }
    }
    private static void readInts(DataInputStream in,int[] values,byte[] buffer)throws IOException {
        for(int start=0;start<values.length;start+=buffer.length/4){int size=Math.min(buffer.length/4,values.length-start);in.readFully(buffer,0,size*4);java.nio.ByteBuffer.wrap(buffer,0,size*4).asIntBuffer().get(values,start,size);}
    }
    static String digits(String letters) {
        StringBuilder result=new StringBuilder();for(int i=0;i<letters.length();i++){char letter=letters.charAt(i);if(letter=='\'')continue;result.append(letter<='c'?'2':letter<='f'?'3':letter<='i'?'4':letter<='l'?'5':letter<='o'?'6':letter<='s'?'7':letter<='v'?'8':'9');}return result.toString();
    }
    List<String> readings(String raw) {
        List<String> result=new ArrayList<>();for(int i=0;i<syllables.length;i++)if(end(raw,0,codes[i])>=0)result.add(syllables[i]);return result;
    }
    private static int end(String raw,int start,String code) {
        int position=start;for(int i=0;i<code.length();i++){if(position>=raw.length()||raw.charAt(position++)!=code.charAt(i))return -1;}
        if(position<raw.length()&&raw.charAt(position)=='\'')position++;return position;
    }
    static final class Match {
        final int row,consumed;final String spelling;final boolean completion;final double priority;
        Match(int row,int consumed,String spelling,boolean completion,double priority){this.row=row;this.consumed=consumed;this.spelling=spelling;this.completion=completion;this.priority=priority;}
    }
    private static final class State {
        final int node,initials;final String spelling;
        State(int node,int initials,String spelling){this.node=node;this.initials=initials;this.spelling=spelling;}
    }
    List<Match> match(String raw,String selected) {
        char[] required=new char[raw.length()];boolean[] hard=new boolean[raw.length()+1],inside=new boolean[raw.length()+1];int selectedOffset=0;
        if(!selected.isEmpty())for(String part:selected.split("'")){int start=selectedOffset;for(int i=0;i<part.length();i++)required[selectedOffset++]=part.charAt(i);if(part.length()>1){hard[start]=true;hard[selectedOffset]=true;for(int i=start+1;i<selectedOffset;i++)inside[i]=true;}if(selectedOffset<raw.length()&&raw.charAt(selectedOffset)=='\'')selectedOffset++;}
        List<Map<Integer,State>> positions=new ArrayList<>();for(int i=0;i<=raw.length();i++)positions.add(new HashMap<>());
        positions.get(0).put(0,new State(0,0,""));List<List<Match>> result=new ArrayList<>();for(int i=0;i<=raw.length();i++)result.add(new ArrayList<>());
        for(int offset=0;offset<raw.length();offset++) {
            if(positions.get(offset).isEmpty())continue;
            List<State> states=new ArrayList<>(positions.get(offset).values());
            states.sort(Comparator.comparingDouble((State state)->Math.log1p(weights[state.node])-.2*state.initials).reversed());
            if(states.size()>BEAM)states.subList(BEAM,states.size()).clear();
            for(State state:states)for(int child=children[state.node];child<children[state.node+1];child++) {
                int label=labels[child];String syllable=syllables[label],code=codes[label];
                if(allowed(required,hard,inside,offset,syllable)) {
                    int next=end(raw,offset,code);
                    if(next>=0)advance(positions,result,state,child,next,syllable,false);
                    else if(offset>=selectedOffset&&raw.indexOf('\'',offset)<0&&code.startsWith(raw.substring(offset))) {
                        String typed=syllable.substring(0,raw.length()-offset);String spelling=joined(state.spelling,typed);
                        collect(result,child,raw.length(),spelling,true,state.initials);
                    }
                }
                if(code.length()>1&&allowed(required,hard,inside,offset,syllable.substring(0,1))) {
                    int next=end(raw,offset,code.substring(0,1));if(next>=0)advance(positions,result,state,child,next,syllable.substring(0,1),true);
                }
            }
        }
        Map<String,Match> found=new LinkedHashMap<>();
        Comparator<Match> ranking=Comparator.comparing((Match match)->match.completion).thenComparing(Comparator.comparingDouble((Match match)->match.priority).reversed());
        for(int offset=raw.length();offset>0;offset--){List<Match> group=result.get(offset);group.sort(ranking);int complete=0,incomplete=0;for(Match match:group){if(match.completion?incomplete>=32:complete>=(offset==raw.length()?128:48))continue;String key=match.row+":"+offset;if(found.containsKey(key))continue;found.put(key,match);if(match.completion)incomplete++;else complete++;}}
        return new ArrayList<>(found.values());
    }
    private static boolean allowed(char[] required,boolean[] hard,boolean[] inside,int start,String spelling) {
        int end=start+spelling.length();if(inside[start]||end<inside.length&&inside[end])return false;
        for(int i=0;i<spelling.length()&&start+i<required.length;i++){int index=start+i;if(required[index]!=0&&required[index]!=spelling.charAt(i)||i>0&&hard[index])return false;}return true;
    }
    private static String joined(String before,String next){return before.isEmpty()?next:before+"'"+next;}
    private void advance(List<Map<Integer,State>> positions,List<List<Match>> found,State before,int node,int offset,String typed,boolean initial) {
        State state=new State(node,before.initials+(initial?1:0),joined(before.spelling,typed));
        State previous=positions.get(offset).get(node);if(previous!=null&&previous.initials<=state.initials)return;
        positions.get(offset).put(node,state);collect(found,node,offset,state.spelling,false,state.initials);
    }
    private void collect(List<List<Match>> results,int node,int offset,String typed,boolean completion,int initials) {
        List<Match> found=results.get(offset);
        for(int i=terminals[node];i<Math.min(terminals[node+1],terminals[node]+48);i++){int row=rowids[i];double priority=Math.log1p(frequencies[row-1])-.2*initials-(completion?.35:0);found.add(new Match(row,offset,typed,completion,priority));}
        if(found.size()>1024){found.sort(Comparator.comparing((Match match)->match.completion).thenComparing(Comparator.comparingDouble((Match match)->match.priority).reversed()));found.subList(512,found.size()).clear();}
    }
}
