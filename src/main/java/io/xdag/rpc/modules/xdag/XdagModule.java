/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.xdag.rpc.modules.xdag;

import io.xdag.rpc.Web3;
import io.xdag.rpc.utils.TypeConverter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Slf4j
public class XdagModule implements XdagModuleTransaction, XdagModuleWallet {

    private static final Logger logger = LoggerFactory.getLogger(XdagModule.class);

    private final XdagModuleWallet xdagModuleWallet;
    private final XdagModuleTransaction xdagModuleTransaction;
    private final byte chainId;


    public XdagModule(byte chainId, XdagModuleWallet xdagModuleWallet, XdagModuleTransaction xdagModuleTransaction) {
        this.chainId = chainId;
        this.xdagModuleWallet = xdagModuleWallet;
        this.xdagModuleTransaction = xdagModuleTransaction;
    }


    public String chainId() {
        return TypeConverter.toJsonHex(new byte[]{chainId});
    }

    @Override
    public String sendTransaction(Web3.CallArguments args) {
        return xdagModuleTransaction.sendTransaction(args);
    }

    @Override
    public String sendRawTransaction(String rawData) {
        return xdagModuleTransaction.sendRawTransaction(rawData);
    }

    @Override
    public String[] accounts() {
        return xdagModuleWallet.accounts();
    }

    @Override
    public String sign(String addr, String data) {
        return xdagModuleWallet.sign(addr, data);
    }
}
