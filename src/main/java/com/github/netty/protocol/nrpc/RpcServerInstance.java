package com.github.netty.protocol.nrpc;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Function;

import static com.github.netty.protocol.nrpc.RpcPacket.RequestPacket;
import static com.github.netty.protocol.nrpc.RpcPacket.ResponsePacket;
import static com.github.netty.protocol.nrpc.RpcPacket.ResponsePacket.*;

/**
 * RPC server instance
 * @author wangzihao
 */
public class RpcServerInstance {
    private Object instance;
    private Map<String, RpcMethod<RpcServerInstance>> rpcMethodMap;
    private DataCodec dataCodec;

    /**
     * A constructor
     * @param instance The implementation class
     * @param dataCodec Data encoding and decoding
     * @param methodToParameterNamesFunction Method to a function with a parameter name
     */
    protected RpcServerInstance(Object instance, DataCodec dataCodec, Function<Method,String[]> methodToParameterNamesFunction) {
        this.instance = instance;
        this.dataCodec = dataCodec;
        this.rpcMethodMap = RpcMethod.getMethodMap(this,instance.getClass(), methodToParameterNamesFunction);
        if(rpcMethodMap.isEmpty()){
            throw new IllegalStateException("An RPC service must have at least one method, class=["+instance.getClass().getSimpleName()+"]");
        }
    }

    public ResponsePacket invoke(RequestPacket rpcRequest, RpcContext<RpcServerInstance> rpcContext){
        ResponsePacket rpcResponse = ResponsePacket.newInstance();
        rpcContext.setResponse(rpcResponse);

        rpcResponse.setRequestId(rpcRequest.getRequestId());
        RpcMethod<RpcServerInstance> rpcMethod = rpcMethodMap.get(rpcRequest.getMethodName());
        rpcContext.setRpcMethod(rpcMethod);
        if(rpcMethod == null) {
            rpcResponse.setEncode(DataCodec.Encode.BINARY);
            rpcResponse.setStatus(NO_SUCH_METHOD);
            rpcResponse.setMessage("not found method [" + rpcRequest.getMethodName() + "]");
            rpcResponse.setData(null);
            return rpcResponse;
        }

        try {
            Object[] args = dataCodec.decodeRequestData(rpcRequest.getData(),rpcMethod);
            rpcContext.setArgs(args);
            Object result = rpcMethod.getMethod().invoke(instance, args);
            rpcContext.setResult(result);
            //Whether to code or not
            if(result instanceof byte[]){
                rpcResponse.setEncode(DataCodec.Encode.BINARY);
                rpcResponse.setData((byte[]) result);
            }else {
                rpcResponse.setEncode(DataCodec.Encode.JSON);
                rpcResponse.setData(dataCodec.encodeResponseData(result,rpcMethod));
            }
            rpcResponse.setStatus(OK);
            rpcResponse.setMessage("ok");
            return rpcResponse;
        }catch (Throwable t){
            rpcContext.setThrowable(t);
            String message = getMessage(t);
            Throwable cause = getCause(t);
            if(cause != null){
                message = message + ". cause=" + getMessage(cause);
            }
            rpcResponse.setEncode(DataCodec.Encode.BINARY);
            rpcResponse.setStatus(SERVER_ERROR);
            rpcResponse.setMessage(message);
            rpcResponse.setData(null);
            return rpcResponse;
        }
    }

    private Throwable getCause(Throwable throwable){
        if(throwable.getCause() == null){
            return null;
        }
        while (true){
            Throwable cause = throwable;
            throwable = throwable.getCause();
            if(throwable == null){
                return cause;
            }
        }
    }

    private String getMessage(Throwable t){
        String message = t.getMessage();
        return message == null? t.toString(): message;
    }

    public Object getInstance() {
        return instance;
    }
}
