package wiki.ica.xiaomiao.mcluaide.client;

import java.util.*;
import java.util.regex.Pattern;

/** Editor-only function metadata and structural folds; offsets always refer to the original source. */
final class LuaFunctionModel {
    private static final Pattern DIRECTIVE = Pattern.compile("--\\s*(?:#(name|Introduction)|@([\\p{L}_][\\p{L}\\p{N}_]*|\\.\\.\\.))\\s*=\\s*", Pattern.CASE_INSENSITIVE);
    final String source;
    final List<Function> functions;
    final List<Fold> folds;
    private final List<LuaCodeAnalyzer.Token> tokens;
    private final int[] lineStarts;

    interface Fold {
        int start(); int declarationStart(); int line(); int endLine(); int hiddenStart(); int hiddenEnd();
        String displayName(); boolean foldable();
    }
    record BlockFold(int start, int declarationStart, int line, int endLine, int hiddenStart, int hiddenEnd,
                     String displayName) implements Fold {
        public boolean foldable() { return endLine > line && hiddenStart < hiddenEnd; }
    }

    record Function(String symbol, int start, int declarationStart, int headerEnd, int end, int line, int endLine,
                    int hiddenStart, int hiddenEnd, int scopeStart, int scopeEnd, boolean local,
                    boolean complete, List<String> parameters, String displayName, String introduction,
                    Map<String,String> notes) implements Fold {
        public boolean foldable() { return complete && endLine > line && hiddenStart < hiddenEnd; }
        boolean visible(int offset) { return !local || offset >= declarationStart && offset >= scopeStart && offset <= scopeEnd; }
        String label() { return displayName.isBlank() ? symbol : symbol + " · " + displayName; }
        List<String> tooltip() {
            List<String> lines = new ArrayList<>();
            lines.add((symbol.isEmpty() ? "function" : symbol) + "(" + String.join(", ", parameters) + ")");
            if (!displayName.isBlank()) lines.add("作用：" + displayName);
            if (!introduction.isBlank()) lines.add("介绍：" + introduction);
            notes.forEach((name, description) -> lines.add((parameters.contains(name) ? "参数 " : "变量 ") + name + "：" + description));
            return List.copyOf(lines);
        }
    }
    private static final class Frame {
        final String kind; final int start; final Builder function; boolean awaitingDo, complete; int end, headerEnd;
        Frame(String kind, int start, int length, Builder function) {
            this.kind=kind; this.start=start; this.end=length; this.function=function;
            this.headerEnd=start;
            awaitingDo=kind.equals("loop");
        }
    }
    private static final class Builder {
        String symbol="",displayName="",introduction=""; boolean local,complete;
        int start,declarationStart,headerEnd,end; Frame owner; List<String> parameters=new ArrayList<>(); Map<String,String> notes=new LinkedHashMap<>();
    }
    private LuaFunctionModel(String source,List<Function> functions,List<Fold> folds,List<LuaCodeAnalyzer.Token> tokens, int[] lineStarts) {
        this.source=source;this.functions=List.copyOf(functions);this.folds=List.copyOf(folds);this.tokens=tokens;this.lineStarts=lineStarts;
    }
    static LuaFunctionModel parse(String source) {
        var tokens=LuaCodeAnalyzer.lex(source);List<Builder> builders=new ArrayList<>();Deque<Frame> blocks=new ArrayDeque<>();
        List<Frame> conditionals=new ArrayList<>();List<Fold> folds=new ArrayList<>();Deque<LuaCodeAnalyzer.Token> tables=new ArrayDeque<>();
        int[] lineStarts = lineStarts(source);
        for(int i=0;i<tokens.size();i++) {
            var token=tokens.get(i);
            if(token.type()==LuaCodeAnalyzer.TokenType.SYMBOL) {
                if(token.text().equals("{"))tables.push(token);
                else if(token.text().equals("}")&&!tables.isEmpty()) {
                    var opening=tables.pop();
                    folds.add(blockFold(source,lineStarts,opening.start(),opening.end(),token.end(),"数组／表"));
                }
            }
            if(token.type()!=LuaCodeAnalyzer.TokenType.KEYWORD)continue;
            switch(token.text()) {
                case "function" -> {
                    Builder b=new Builder();b.start=token.start();b.declarationStart=b.start;b.end=source.length();b.owner=blocks.peek();
                    int next=i+1;
                    if(next<tokens.size()&&tokens.get(next).type()==LuaCodeAnalyzer.TokenType.IDENTIFIER) {
                        StringBuilder symbol=new StringBuilder(tokens.get(next++).text());
                        while(next+1<tokens.size()&&Set.of(".",":").contains(tokens.get(next).text())&&tokens.get(next+1).type()==LuaCodeAnalyzer.TokenType.IDENTIFIER)
                            symbol.append(tokens.get(next++).text()).append(tokens.get(next++).text());
                        b.symbol=symbol.toString();b.local=i>0&&tokens.get(i-1).text().equals("local");
                        if (b.local) b.declarationStart=tokens.get(i-1).start();
                    } else if(i>=2&&tokens.get(i-1).text().equals("=")) {
                        int first=i-2;
                        while(first>=2&&Set.of(".",":").contains(tokens.get(first-1).text())&&tokens.get(first-2).type()==LuaCodeAnalyzer.TokenType.IDENTIFIER)first-=2;
                        if(tokens.get(first).type()==LuaCodeAnalyzer.TokenType.IDENTIFIER) {
                            b.symbol=source.substring(tokens.get(first).start(),tokens.get(i-2).end()).replaceAll("\\s+","");
                            b.local=first>0&&tokens.get(first-1).text().equals("local");
                            b.declarationStart=tokens.get(b.local?first-1:first).start();
                        }
                    }
                    if(next>=tokens.size()||!tokens.get(next).text().equals("("))continue;
                    int close=next+1;
                    while(close<tokens.size()&&!tokens.get(close).text().equals(")")) {
                        var param=tokens.get(close);
                        if(param.type()==LuaCodeAnalyzer.TokenType.IDENTIFIER)b.parameters.add(param.text());
                        else if(param.text().equals("..")&&close+1<tokens.size()&&tokens.get(close+1).text().equals(".")){b.parameters.add("...");close++;}
                        close++;
                    }
                    if(close>=tokens.size())continue;
                    b.headerEnd=tokens.get(close).end();readComments(source,b);builders.add(b);
                    blocks.push(new Frame("function",b.start,source.length(),b));i=close;
                }
                case "if" -> {
                    Frame conditional=new Frame("if",token.start(),source.length(),null);
                    blocks.push(conditional);conditionals.add(conditional);
                }
                case "then" -> {
                    if (!blocks.isEmpty() && blocks.peek().kind.equals("if")) {
                        if(blocks.peek().headerEnd==blocks.peek().start)blocks.peek().headerEnd=token.end();
                        blocks.push(new Frame("branch", token.end(), source.length(), null));
                    }
                }
                case "elseif", "else" -> {
                    if (!blocks.isEmpty() && blocks.peek().kind.equals("branch")) {
                        blocks.pop().end = token.start();
                        if (token.text().equals("else"))
                            blocks.push(new Frame("branch", token.end(), source.length(), null));
                    }
                }
                case "for","while" -> blocks.push(new Frame("loop",token.start(),source.length(),null));
                case "repeat" -> blocks.push(new Frame("repeat",token.start(),source.length(),null));
                case "do" -> {
                    if(!blocks.isEmpty()&&blocks.peek().awaitingDo)blocks.peek().awaitingDo=false;
                    else blocks.push(new Frame("do",token.start(),source.length(),null));
                }
                case "end","until" -> {
                    if(blocks.isEmpty())continue;
                    if (token.text().equals("end") && blocks.peek().kind.equals("branch")) {
                        blocks.pop().end = token.start();
                        if (blocks.isEmpty()) continue;
                    }
                    if(token.text().equals("until")&&!blocks.peek().kind.equals("repeat"))continue;
                    if(token.text().equals("end")&&blocks.peek().kind.equals("repeat"))continue;
                    Frame f=blocks.pop();f.end=token.end();f.complete=true;
                    if(f.function!=null){f.function.end=token.end();f.function.complete=true;}
                }
            }
        }
        List<Function> functions=new ArrayList<>();
        for(Builder b:builders) {
            int line=lineAt(lineStarts,b.start),endLine=lineAt(lineStarts,Math.max(b.start,b.end-1));
            int hiddenEnd = hiddenEnd(source, b.end);
            functions.add(new Function(b.symbol,b.start,b.declarationStart,b.headerEnd,b.end,line,endLine,lineAfter(source,b.headerEnd),hiddenEnd,
                    b.owner==null?0:b.owner.start,b.owner==null?source.length():b.owner.end,b.local,b.complete,List.copyOf(b.parameters),b.displayName,b.introduction,Collections.unmodifiableMap(new LinkedHashMap<>(b.notes))));
        }
        folds.addAll(functions);
        for(var conditional:conditionals)if(conditional.complete&&conditional.headerEnd>conditional.start)
            folds.add(blockFold(source,lineStarts,conditional.start,conditional.headerEnd,conditional.end,"if … end"));
        folds.sort(Comparator.comparingInt(Fold::start));
        return new LuaFunctionModel(source,functions,folds,tokens,lineStarts);
    }
    private static BlockFold blockFold(String source,int[] lines,int start,int headerEnd,int end,String label) {
        return new BlockFold(start,start,lineAt(lines,start),lineAt(lines,end-1),lineAfter(source,headerEnd),hiddenEnd(source,end),label);
    }
    private static int hiddenEnd(String source,int end) {
        int afterLine=lineAfter(source,end);String tail=source.substring(end,afterLine).strip();
        return !tail.isEmpty()&&!tail.startsWith("--") ? source.lastIndexOf('\n',Math.max(0,end-1))+1 : afterLine;
    }
    private static int[] lineStarts(String source) {
        int count=1;for(int i=0;i<source.length();i++)if(source.charAt(i)=='\n')count++;
        int[] starts=new int[count];int line=1;
        for(int i=0;i<source.length();i++)if(source.charAt(i)=='\n')starts[line++]=i+1;
        return starts;
    }
    private static int lineAt(int[] starts,int offset) {int index=Arrays.binarySearch(starts,offset);return index>=0?index+1:-index-1;}
    int lineAt(int offset) {return lineAt(lineStarts,offset);}
    private static void readComments(String source,Builder b) {
        int p=b.headerEnd;
        while(p<source.length()) {
            while(p<source.length()&&Character.isWhitespace(source.charAt(p)))p++;
            if(!source.startsWith("--",p))break;
            int bracket=p+2,level=0;
            if(bracket<source.length()&&source.charAt(bracket)=='[') {
                int q=bracket+1;while(q<source.length()&&source.charAt(q)=='='){q++;level++;}
                if(q<source.length()&&source.charAt(q)=='[') {int end=source.indexOf("]"+"=".repeat(level)+"]",q+1);p=end<0?source.length():end+level+2;continue;}
            }
            int end=source.indexOf('\n',p);if(end<0)end=source.length();String comment=source.substring(p,end).stripTrailing();
            var matcher=DIRECTIVE.matcher(comment);
            if(matcher.lookingAt()) {
                do {
                    String kind=matcher.group(1),variable=matcher.group(2);int valueStart=matcher.end();
                    boolean another=matcher.find();int valueEnd=another?matcher.start():comment.length();String value=comment.substring(valueStart,valueEnd).strip();
                    if(kind==null)b.notes.put(variable,value);else if(kind.equalsIgnoreCase("name"))b.displayName=value;else b.introduction=value;
                    if(!another)break;
                }while(true);
            }
            p=end;
        }
    }
    Fold atLine(int line) {return folds.stream().filter(f->f.line()==line&&f.foldable()).findFirst().orElse(null);}
    Function find(String symbol,int offset) {
        return functions.stream().filter(f->!f.symbol.isEmpty()&&f.symbol.replace(':','.').equals(symbol.replace(':','.'))&&f.visible(offset))
                .sorted(Comparator.comparingInt((Function f)->f.local?0:1).thenComparingInt(f->f.scopeEnd-f.scopeStart).thenComparingInt(f->f.start<=offset?-f.start:f.start))
                .findFirst().orElse(null);
    }
    List<String> symbols(int cursor) {return functions.stream().filter(f->!f.symbol.isEmpty()&&f.visible(cursor)).map(Function::symbol).distinct().toList();}
    List<String> variableTooltip(String name,int offset) {
        for(var f:functions.stream().filter(f->offset>=f.start&&offset<f.end).sorted(Comparator.comparingInt(Function::start).reversed()).toList()) {
            if(f.notes.containsKey(name))return List.of("变量："+name,f.notes.get(name),"所属函数："+(f.displayName.isBlank()?f.symbol:f.displayName));
            if(f.parameters.contains(name))return List.of();
        }
        return List.of();
    }
    boolean identifierAt(int offset) {return tokens.stream().anyMatch(t->offset>=t.start()&&offset<t.end()&&t.type()==LuaCodeAnalyzer.TokenType.IDENTIFIER);}
    static int lineAt(String source,int offset) {int line=1;for(int i=0;i<Math.min(offset,source.length());i++)if(source.charAt(i)=='\n')line++;return line;}
    static int lineAfter(String source,int offset) {int end=source.indexOf('\n',offset);return end<0?source.length():end+1;}
}
