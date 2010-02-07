package cascading.clojure;

import clojure.lang.RT;
import clojure.lang.IFn;
import clojure.lang.ISeq;
import clojure.lang.IteratorSeq;
import clojure.lang.ArraySeq;
import cascading.tuple.Tuple;
import cascading.operation.OperationCall;
import java.util.Collection;

public class Util {
  public static IFn bootSimpleFn(String ns_name, String fn_name) {
    String root_path = ns_name.replace('-', '_').replace('.', '/');
    try {
      RT.load(root_path);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return (IFn) RT.var(ns_name, fn_name).deref();
  }
  
  public static IFn bootFn(Object[] fn_spec) {
    String ns_name = (String) fn_spec[0];
    String fn_name = (String) fn_spec[1];
    IFn simple_fn = bootSimpleFn(ns_name, fn_name);
    if (fn_spec.length == 2) {
      return simple_fn;
    } else {
      ISeq hof_args = ArraySeq.create(fn_spec).next().next();
      try {
        return (IFn) simple_fn.applyTo(hof_args);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }
  
  public static ISeq coerceFromTuple(Tuple tuple) {
    return IteratorSeq.create(tuple.iterator());
  }
    
  public static Tuple coerceToTuple(Object obj) {
    if(obj instanceof Collection) {
      Object[] raw_arr = ((Collection)obj).toArray();
      Comparable[] arr = new Comparable[raw_arr.length];
      System.arraycopy(raw_arr, 0, arr, 0, raw_arr.length);
      return new Tuple(arr);
    } else {
      return new Tuple((Comparable) obj);
    }
  }
  
  public static boolean truthy(Object obj) {
    return ((obj != null) && (!Boolean.FALSE.equals(obj)));
  }
}
